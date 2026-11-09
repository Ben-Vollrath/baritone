/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.look.ForkableRandom;
import baritone.pathing.movement.MovementHelper;
import baritone.process.CropSweepModel.Arc;
import baritone.process.CropSweepModel.Cell;
import baritone.process.CropSweepModel.Point;
import baritone.process.CropSweepModel.Stroke;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Harvests a field the way a person does: sprinting over cleared ground with attack held, a few
 * blocks back from the uncut edge. Each camera stroke crosses the crops actually in reach and
 * turns back at the last of them, and the strafe key follows the stroke, which makes the zigzag.
 * With nothing in reach the camera just faces the way the body is going. Passes set that way,
 * and a pass runs on while crops remain ahead.
 */
final class CropSweep {
    private static final int PREDICTION_TICKS = 8, CROP_PREDICTION_TICKS = 4, STUCK_TICKS = 30, FALLBACK_TICKS = 100, PICK_TICKS = 60;
    private static final double PASS_RADIUS = 4, TURN_PER_TICK = 9, ARRIVAL = 1.7;
    private static final double NORMAL_WALK_SPEED = .1, SPRINT_LIMIT = .18, PICK_STANDOFF = 2;
    private static final int MOP_UP_MIN = 6;
    private static final double MOP_UP_SHARE = .1, PASS_TICKS_PER_BLOCK = 22;
    /** Crops the crosshair can take at sweep pitch, and how far to the side of travel it looks for them. */
    private static final double REACH_NEAR = 1.4, REACH_FAR = 4.4, FRONT_ARC = 110;
    /** Crops within this of straight ahead keep a pass going past its planned end. */
    private static final double AHEAD_ARC = 50, AHEAD_RANGE = 6;
    /** An arc narrower than this is one crop or so: aim straight at it rather than swinging. */
    private static final double NARROW_ARC = 12;
    private static final double TRAVEL_PITCH = 19;
    /** About one full zigzag, so the measurement sees both a left and a right stroke. */
    private static final int DRIFT_WINDOW = 26;
    private static final double DRIFT_GAIN = .05, DRIFT_MAX_ERROR = 75, DRIFT_MAX_CORRECTION = 50;

    private record Keys(boolean forward, boolean left, boolean right) {
        static final Keys NONE = new Keys(false, false, false);
        static final Keys FORWARD = new Keys(true, false, false);
    }

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private final Predicate<BlockState> ready;
    private final ArrayDeque<Point> passes = new ArrayDeque<>();
    private final Set<BlockPos> skipped = new HashSet<>();
    private final ArrayDeque<double[]> recent = new ArrayDeque<>();

    private ForkableRandom random;
    private boolean planned, harvesting, forwardDown;
    private double travelYaw, driftCorrection, strokeFromYaw, lastPitch;
    private Stroke stroke;
    private int strokeAge, startDelay, tapTicks, switchTicks, overlapTicks, stuckTicks, fallbackTicks, targetTicks, headingTicks, plannedCrops;
    private Point heading, passFrom;
    private BlockPos target;
    private double lastX, lastZ;

    CropSweep(Baritone baritone, Predicate<BlockState> ready) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.ready = ready;
    }

    void reset() {
        passes.clear();
        skipped.clear();
        random = null;
        planned = harvesting = false;
        stroke = null;
        target = null;
        heading = passFrom = null;
        stuckTicks = fallbackTicks = 0;
    }

    /** Whether this sweep broke into the field; an empty field then means it is finished, not failed. */
    boolean harvested() {
        return harvesting;
    }

    /** Held only over a ripe crop or empty air, so the sweep never digs into anything else. */
    boolean isHoldingAttack() {
        if (!harvesting || fallbackTicks > 0 || ctx.player() == null) return false;
        HitResult hit = ctx.objectMouseOver();
        return !(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK
                || ready.test(ctx.world().getBlockState(block.getBlockPos()));
    }

    /** @return the command for this tick, or null to leave the tick to the ordinary farming logic */
    PathingCommand tick(List<BlockPos> ripe) {
        int feet = ctx.playerFeet().getY();
        List<BlockPos> crops = new ArrayList<>();
        for (BlockPos pos : ripe) {
            if (Math.abs(pos.getY() - feet) <= 1 && !skipped.contains(pos)) crops.add(pos);
        }
        if (crops.isEmpty()) return null;
        if (fallbackTicks > 0) {
            fallbackTicks--;
            return null;
        }
        double x = ctx.player().getX(), z = ctx.player().getZ();
        if (random == null) start(x, z);
        if (!planned) plan(crops, x, z);
        trackStuck(x, z);
        if (fallbackTicks > 0) return null;
        if (passes.isEmpty() && leadsToAnotherField(crops, x, z)) {
            planned = false;
            target = null;
            return pause();
        }

        double route = routeBearing(crops, x, z);
        correctDrift(route, x, z);
        double wanted = route + driftCorrection;
        travelYaw += Math.max(-TURN_PER_TICK, Math.min(TURN_PER_TICK, Mth.wrapDegrees(wanted - travelYaw)));

        double speed = ctx.player().getSpeed() / (ctx.player().isSprinting() ? 1.3 : 1);
        double timeScale = Math.max(.5, Math.min(1, NORMAL_WALK_SPEED / speed));
        BetterBlockPos standing = ctx.playerFeet();
        BlockStateInterface bsi = new BlockStateInterface(ctx);

        Arc arc = cropsInReach(crops, x, z);
        Keys keys;
        if (arc == null) {
            // Nothing to aim at: no swinging, just look where the body is going.
            stroke = null;
            keys = Keys.FORWARD;
            baritone.getLookBehavior().updateTarget(new Rotation((float) travelYaw, (float) TRAVEL_PITCH), false);
        } else if (arc.width() < NARROW_ARC) {
            keys = aimAtOneCrop(crops, x, z);
        } else {
            advanceStroke(arc, timeScale);
            keys = sweepKeys();
            aimStroke(arc);
        }
        if (startDelay > 0) {
            startDelay--;
            keys = Keys.NONE;
        }
        apply(chooseKeys(keys, crops, bsi, standing, speed), speed);
        updateAttack();
        return pause();
    }

    private void start(double x, double z) {
        random = new ForkableRandom(Baritone.settings().naturalMovementSeed.value ^ 0x51ed270b7a3f9c41L);
        travelYaw = ctx.player().getYRot();
        lastPitch = ctx.player().getXRot();
        driftCorrection = 0;
        recent.clear();
        startDelay = 2 + (int) (random.nextDouble() * 4);
        lastX = x;
        lastZ = z;
        forwardDown = true;
        tapTicks = 0;
    }

    private void plan(List<BlockPos> crops, double x, double z) {
        passes.clear();
        List<Cell> field = CropSweepModel.nearestField(cells(crops), x, z);
        passes.addAll(CropSweepModel.passes(field, x, z, ctx.player().getYRot(), random));
        plannedCrops = field.size();
        planned = true;
        MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(crops.getFirst()));
    }

    private static List<Cell> cells(List<BlockPos> crops) {
        List<Cell> cells = new ArrayList<>(crops.size());
        for (BlockPos pos : crops) cells.add(new Cell(pos.getX(), pos.getZ()));
        return cells;
    }

    private void trackStuck(double x, double z) {
        boolean moved = Math.hypot(x - lastX, z - lastZ) > .02;
        lastX = x;
        lastZ = z;
        // Standing still is fine while something is being cut; the crops set the pace.
        boolean cutting = ctx.objectMouseOver() instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK
                && ready.test(ctx.world().getBlockState(block.getBlockPos()));
        stuckTicks = moved || cutting || startDelay > 0 ? 0 : stuckTicks + 1;
        if (stuckTicks < STUCK_TICKS) return;
        // Let the pathfinder walk out, then plan again from wherever that leaves the player.
        stuckTicks = 0;
        fallbackTicks = FALLBACK_TICKS;
        planned = false;
        stroke = null;
    }

    /** The compass bearing the body should follow this tick. */
    private double routeBearing(List<BlockPos> crops, double x, double z) {
        // With only a little left, go straight for it instead of finishing the planned passes.
        if (crops.size() <= Math.max(MOP_UP_MIN, plannedCrops * MOP_UP_SHARE)) passes.clear();
        while (!passes.isEmpty()) {
            Point next = passes.peek();
            if (!next.equals(heading)) {
                heading = next;
                passFrom = new Point(x, z);
                headingTicks = (int) (Math.hypot(next.x() - x, next.z() - z) * PASS_TICKS_PER_BLOCK) + 40;
            }
            double along = bearing(next.x() - passFrom.x(), next.z() - passFrom.z());
            boolean arrived = CropSweepModel.arrived(x, z, passFrom, next, ARRIVAL);
            // A pass runs on while crops remain ahead, instead of turning back at its planned end.
            if (arrived && cropsAhead(crops, x, z, along) && headingTicks > 0) {
                headingTicks--;
                return along;
            }
            boolean done = arrived || headingTicks-- <= 0;
            if (!done && worthwhile(crops, x, z, next)) return bearing(next.x() - x, next.z() - z);
            passes.poll();
        }
        BlockPos crop = nextTarget(crops, x, z);
        return bearing(crop.getX() + .5 - x, crop.getZ() + .5 - z);
    }

    private static double bearing(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    private boolean cropsAhead(List<BlockPos> crops, double x, double z, double along) {
        for (BlockPos crop : crops) {
            double dx = crop.getX() + .5 - x, dz = crop.getZ() + .5 - z;
            if (Math.hypot(dx, dz) <= AHEAD_RANGE && Math.abs(Mth.wrapDegrees(bearing(dx, dz) - along)) <= AHEAD_ARC) return true;
        }
        return false;
    }

    /** Whether what remains is a separate field big enough to deserve passes of its own. */
    private boolean leadsToAnotherField(List<BlockPos> crops, double x, double z) {
        if (crops.size() <= MOP_UP_MIN) return false;
        return CropSweepModel.nearestField(cells(crops), x, z).size() > Math.max(MOP_UP_MIN, plannedCrops * MOP_UP_SHARE);
    }

    private boolean worthwhile(List<BlockPos> crops, double x, double z, Point next) {
        Point from = new Point(x, z);
        for (BlockPos crop : crops) {
            if (CropSweepModel.distanceToSegment(crop.getX() + .5, crop.getZ() + .5, from, next) <= PASS_RADIUS) return true;
        }
        return false;
    }

    /** The next leftover to head for, keeping to it and preferring what lies ahead over a turn back. */
    private BlockPos nextTarget(List<BlockPos> crops, double x, double z) {
        if (target != null && crops.contains(target) && targetTicks++ < PICK_TICKS * 2) return target;
        if (target != null && crops.contains(target)) skipped.add(target);
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        for (BlockPos crop : crops) {
            if (crop.equals(target)) continue;
            double dx = crop.getX() + .5 - x, dz = crop.getZ() + .5 - z;
            double cost = CropSweepModel.targetCost(Math.hypot(dx, dz), Mth.wrapDegrees(bearing(dx, dz) - travelYaw));
            if (cost < bestCost) {
                bestCost = cost;
                best = crop;
            }
        }
        target = best != null ? best : crops.getFirst();
        targetTicks = 0;
        return target;
    }

    /** The span of crops the crosshair can take, relative to the way the body is going. */
    private Arc cropsInReach(List<BlockPos> crops, double x, double z) {
        List<Double> bearings = new ArrayList<>();
        for (BlockPos crop : crops) {
            double dx = crop.getX() + .5 - x, dz = crop.getZ() + .5 - z;
            double distance = Math.hypot(dx, dz);
            if (distance < REACH_NEAR || distance > REACH_FAR) continue;
            double relative = Mth.wrapDegrees(bearing(dx, dz) - travelYaw);
            if (Math.abs(relative) <= FRONT_ARC) bearings.add(relative);
        }
        return CropSweepModel.arc(bearings.stream().mapToDouble(Double::doubleValue).toArray());
    }

    /** A lone crop in reach: look straight at it and close in until the held attack takes it. */
    private Keys aimAtOneCrop(List<BlockPos> crops, double x, double z) {
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos crop : crops) {
            double distance = Math.hypot(crop.getX() + .5 - x, crop.getZ() + .5 - z);
            if (distance < best && Math.abs(Mth.wrapDegrees(bearing(crop.getX() + .5 - x, crop.getZ() + .5 - z) - travelYaw)) <= FRONT_ARC) {
                best = distance;
                nearest = crop;
            }
        }
        stroke = null;
        if (nearest == null) return Keys.FORWARD;
        Vec3 eyes = ctx.player().getEyePosition(1.0F);
        Rotation rotation = RotationUtils.calcRotationFromVec3d(eyes, Vec3.atCenterOf(nearest), ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rotation, false);
        harvesting = true;
        return best > PICK_STANDOFF ? Keys.FORWARD : Keys.NONE;
    }

    /** Reverses at the end of the crops, or when the stroke's time is up. */
    private void advanceStroke(Arc arc, double timeScale) {
        double yaw = ctx.player().getYRot();
        if (stroke == null) {
            // Start toward whichever end of the crops is further from the view.
            double relative = Mth.wrapDegrees(yaw - travelYaw);
            beginStroke(arc.high() - relative >= relative - arc.low() ? 1 : -1, arc, timeScale);
            return;
        }
        strokeAge++;
        double end = CropSweepModel.strokeEnd(arc, stroke);
        boolean reached = stroke.side() * Mth.wrapDegrees(yaw - (travelYaw + end)) >= -3;
        if (strokeAge >= stroke.ticks() || reached && strokeAge >= stroke.ticks() * .75) beginStroke(-stroke.side(), arc, timeScale);
    }

    private void beginStroke(int side, Arc arc, double timeScale) {
        strokeFromYaw = ctx.player().getYRot();
        double provisional = side > 0 ? arc.high() : arc.low();
        double degrees = Math.abs(Mth.wrapDegrees(travelYaw + provisional - strokeFromYaw));
        stroke = CropSweepModel.stroke(side, degrees, random, timeScale);
        strokeAge = 0;
        // Between strafe keys there is usually a moment on W alone, and now and then both are down.
        double roll = random.nextDouble();
        switchTicks = roll < .5 ? 1 + (int) (random.nextDouble() * 2) : 0;
        overlapTicks = roll > .85 ? 1 : 0;
        forwardDown = true;
        tapTicks = CropSweepModel.forwardTapTicks(true, random);
    }

    private void aimStroke(Arc arc) {
        double end = CropSweepModel.strokeEnd(arc, stroke);
        double travel = Mth.wrapDegrees(travelYaw + end - strokeFromYaw);
        double yaw = strokeFromYaw + travel * CropSweepModel.strokeProgress((double) strokeAge / stroke.ticks());
        lastPitch = stroke.pitch();
        baritone.getLookBehavior().updateTarget(new Rotation((float) yaw, (float) stroke.pitch()), false);
    }

    private Keys sweepKeys() {
        if (switchTicks > 0) {
            switchTicks--;
            return Keys.FORWARD;
        }
        if (overlapTicks > 0) {
            overlapTicks--;
            return new Keys(true, true, true);
        }
        if (stroke.side() > 0) return new Keys(true, false, true);
        // The recordings hold W through right strafes but keep tapping it through left ones.
        if (--tapTicks <= 0) {
            forwardDown = !forwardDown;
            tapTicks = CropSweepModel.forwardTapTicks(forwardDown, random);
        }
        return new Keys(forwardDown, true, false);
    }

    /** The wanted keys if they keep to safe, cleared ground; otherwise the nearest that do. */
    private Keys chooseKeys(Keys wanted, List<BlockPos> crops, BlockStateInterface bsi, BetterBlockPos standing, double speed) {
        Set<Long> uncut = new HashSet<>(crops.size());
        for (BlockPos crop : crops) uncut.add(BlockPos.asLong(crop.getX(), standing.getY(), crop.getZ()));
        if (wanted.equals(Keys.NONE) || clear(wanted, uncut, bsi, standing, speed)) return wanted;
        Keys left = new Keys(false, true, false), right = new Keys(false, false, true);
        // The recordings often strafe left without W, and rarely strafe right without it.
        Keys[] options = wanted.right() ? new Keys[]{Keys.FORWARD, right, left} : new Keys[]{left, Keys.FORWARD, right};
        for (Keys option : options) {
            if (clear(option, uncut, bsi, standing, speed)) return option;
        }
        return Keys.NONE;
    }

    /** Follows this input a few ticks ahead with vanilla ground physics, checking the body's corners. */
    private boolean clear(Keys keys, Set<Long> uncut, BlockStateInterface bsi, BetterBlockPos standing, double speed) {
        Map<Long, Boolean> cells = new HashMap<>();
        boolean startsHazard = hazard(bsi, standing.getX(), standing.getY(), standing.getZ(), cells);
        // Already among crops, the only way out is through them.
        boolean startsInCrops = uncut.contains(BlockPos.asLong(standing.getX(), standing.getY(), standing.getZ()));
        double yaw = Math.toRadians(ctx.player().getYRot());
        double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw), rightX = -Math.cos(yaw), rightZ = -Math.sin(yaw);
        double inputX = (keys.forward() ? forwardX : 0) + (keys.right() ? rightX : 0) - (keys.left() ? rightX : 0);
        double inputZ = (keys.forward() ? forwardZ : 0) + (keys.right() ? rightZ : 0) - (keys.left() ? rightZ : 0);
        double length = Math.hypot(inputX, inputZ);
        if (length > 1e-6) {
            inputX /= length;
            inputZ /= length;
        }
        double acceleration = speed * (keys.forward() && speed < SPRINT_LIMIT ? 1.3 : 1);
        Vec3 motion = ctx.player().getDeltaMovement();
        double vx = motion.x, vz = motion.z, x = ctx.player().getX(), z = ctx.player().getZ();
        for (int tick = 0; tick < PREDICTION_TICKS; tick++) {
            vx += inputX * acceleration;
            vz += inputZ * acceleration;
            x += vx;
            z += vz;
            vx *= .546;
            vz *= .546;
            for (double cornerX = -.3; cornerX <= .3; cornerX += .6) {
                for (double cornerZ = -.3; cornerZ <= .3; cornerZ += .6) {
                    int cellX = Mth.floor(x + cornerX), cellZ = Mth.floor(z + cornerZ);
                    if (!startsHazard && hazard(bsi, cellX, standing.getY(), cellZ, cells)) return false;
                    if (!startsInCrops && tick < CROP_PREDICTION_TICKS && uncut.contains(BlockPos.asLong(cellX, standing.getY(), cellZ))) return false;
                }
            }
        }
        return true;
    }

    private boolean hazard(BlockStateInterface bsi, int x, int y, int z, Map<Long, Boolean> cells) {
        return cells.computeIfAbsent(BlockPos.asLong(x, y, z), key -> !MovementHelper.canWalkOn(bsi, x, y - 1, z)
                || !MovementHelper.canWalkThrough(bsi, x, y, z) || !MovementHelper.canWalkThrough(bsi, x, y + 1, z));
    }

    /**
     * Right strokes keep W down and left strokes tap it, so a zigzag slides to one side. This
     * compares where the body has actually been going with where it should go and steers off the
     * difference. It holds still through sharp turns, when the two are expected to disagree.
     */
    private void correctDrift(double route, double x, double z) {
        recent.addLast(new double[]{x, z});
        if (recent.size() <= DRIFT_WINDOW) return;
        double[] then = recent.removeFirst();
        double dx = x - then[0], dz = z - then[1];
        if (Math.hypot(dx, dz) < 1) return;
        double error = Mth.wrapDegrees(route - bearing(dx, dz));
        if (Math.abs(error) > DRIFT_MAX_ERROR) return;
        driftCorrection = Math.max(-DRIFT_MAX_CORRECTION, Math.min(DRIFT_MAX_CORRECTION, driftCorrection + DRIFT_GAIN * error));
    }

    private void apply(Keys keys, double speed) {
        var input = baritone.getInputOverrideHandler();
        input.setInputForceState(Input.MOVE_FORWARD, keys.forward());
        input.setInputForceState(Input.MOVE_LEFT, keys.left());
        input.setInputForceState(Input.MOVE_RIGHT, keys.right());
        input.setInputForceState(Input.SPRINT, keys.forward() && speed < SPRINT_LIMIT);
    }

    private void updateAttack() {
        HitResult hit = ctx.objectMouseOver();
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                && ready.test(ctx.world().getBlockState(block.getBlockPos()))) harvesting = true;
    }

    private static PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }
}
