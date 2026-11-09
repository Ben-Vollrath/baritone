/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.look.ForkableRandom;
import baritone.behavior.look.NaturalSteering;
import baritone.pathing.movement.MovementHelper;
import baritone.process.SandSweepModel.Candidate;
import baritone.process.SandSweepModel.Cell;
import baritone.process.SandSweepModel.Keys;
import baritone.process.SandSweepModel.Plan;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Mines exposed sand the way a person does with a shovel that breaks it instantly. Attack stays
 * held. The body works around the field's rim a couple of blocks back from the live sand, then
 * inward, and never stands still. The camera scribbles over the patch in front of it: each stroke
 * ends just past a live cell on the other side of the view and breaks whatever it crosses, and the
 * strafe key follows the stroke. Mined sand stays solid, so the ground never opens up.
 */
final class SandSweep {
    private static final int SCAN_RADIUS = 28, SCAN_HEIGHT = 4, SCAN_TICKS = 20;
    /** Beyond this the pathfinder walks to the sand; nearer, a person simply runs over. */
    private static final double CLOSE_RANGE = 16;
    /** Live cells the body and camera work on at a time, as in the recordings' about 20 within reach. */
    private static final int PATCH = 18, CANDIDATES = 40;
    /** The recordings break nothing much closer than this. */
    private static final double MIN_RANGE = 1.1, TRAVEL_PITCH = 30;
    /** The next cell stays this close, so it can always be reached. */
    private static final double HEAD_REACH = 3.6;
    /** Past this the stroke goes straight for the next cell instead of leaving it behind. */
    private static final int FORCE_HEAD_TICKS = 30;
    /** Only a body with this far to go climbs a step; a shuffle beside a dune stays on the ground. */
    private static final double CLIMB_DISTANCE = 1.8, CLIMB_ARC = 35;
    private static final int LIVE_PREDICTION_TICKS = 4, RETRIES = 2;
    /**
     * The body moves on at a person's pace instead of waiting for every cell, and the islands it
     * leaves get collected on a later pass. The cursor runs through the order a little faster than
     * the camera can break, and waits only while too many live cells behind it are within reach.
     */
    private static final double CURSOR_PER_TICK = .42;
    private static final int MAX_BEHIND = 3, CURSOR_WAIT_TICKS = 40;
    /** A cell behind the cursor and this far out is about to be out of reach: the next stroke goes back for it. */
    private static final double LEAVING_RANGE = 3;
    private static final double REACH_RANGE = 4.2;
    /** Live sand closer than this pushes the body away. The recordings stay about two blocks from it. */
    private static final double KEEP_AWAY = 2.2, KEEP_AWAY_GAIN = 1.5;
    /** How close the camera gets to a stroke's end before it turns back. */
    private static final double ARRIVED_YAW = 7, ARRIVED_PITCH = 5, STROKE_TIME_MARGIN = 1.6;
    /** Pitch moves at this share of the speed its remaining travel would give under the motor's response. */
    private static final double PITCH_RESPONSE = .5;
    /** The crosshair stays this close to the eyes when choosing a cell; the recordings break out to about 4.1. */
    private static final double CELL_REACH = 3.9;
    /** Below this share of a field, what is left is scattered: go from one bit to the next. */
    private static final double MOP_UP_SHARE = .06;
    private static final int MOP_UP_MIN = 6, MOP_UP_FORCE_TICKS = 6;
    private static final double MOP_UP_STANDOFF = 2.3;
    /** How strongly the body prefers the outer side of the band it is working. */
    private static final double OUTWARD_BIAS = .45;
    /** With no cell in view from here, the body walks this close to the next one. */
    private static final double NO_VIEW_STANDOFF = 1.6;
    private static final int PREDICTION_TICKS = 6, STUCK_TICKS = 30, FALLBACK_TICKS = 60, HEAD_TICKS = 200;
    private static final double NORMAL_WALK_SPEED = .1, SPRINT_LIMIT = .18;
    private static final double[] CORNERS = {-.3, .3};

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private final Predicate<BlockState> wanted;
    private final Set<Cell> live = new LinkedHashSet<>();
    private final Set<Cell> skipped = new HashSet<>();

    private ForkableRandom random;
    private Plan plan;
    private List<Cell> order = List.of();
    private double standoff, cursor, bandX, bandZ, strokeSpeed, motorLag;
    private int cursorWait, pitchDirection = 1, fieldSize;
    private boolean mopping, noView;
    private int highestFeet, scanIn, headTicks, stuckTicks, fallbackTicks, startDelay, bands, retries;
    private Cell head;
    private boolean working, pathing, forwardDown;
    private int side, strokeAge, strokeTicks, keyHold, tapTicks, switchTicks;
    private boolean stroking;
    private double toYaw, toPitch, overshoot;
    private Cell strokeCell;
    private Keys keys = Keys.NONE;
    private boolean jumpWanted;
    private double lastX, lastZ;

    SandSweep(Baritone baritone, Predicate<BlockState> wanted) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.wanted = wanted;
    }

    void reset() {
        random = null;
        order = List.of();
        live.clear();
        skipped.clear();
        head = strokeCell = null;
        working = pathing = stroking = false;
        scanIn = headTicks = stuckTicks = fallbackTicks = bands = retries = cursorWait = highestFeet = 0;
        cursor = 0;
        fieldSize = 0;
        mopping = false;
        keys = Keys.NONE;
    }

    /** Held over sand, empty air, or what mined sand leaves behind; never over anything that could break. */
    boolean isHoldingAttack() {
        if (!working || ctx.player() == null) return false;
        HitResult hit = ctx.objectMouseOver();
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return true;
        BlockState state = ctx.world().getBlockState(block.getBlockPos());
        return wanted.test(state) || state.getDestroySpeed(ctx.world(), block.getBlockPos()) < 0;
    }

    BlockPos target() {
        return head == null ? null : new BlockPos(head.x(), head.y(), head.z());
    }

    int bandsStarted() {
        return bands;
    }

    /** The pathfinder found no way to the sand it was heading for. */
    void abandonTarget() {
        if (head != null) skipped.add(head);
        order = List.of();
        head = null;
        pathing = false;
    }

    /** @return the command for this tick, or null when no sand it can get to is left */
    PathingCommand tick(boolean safeToCancel) {
        if (random == null) start();
        scan();
        List<Cell> remaining = remaining();
        if (remaining.isEmpty()) {
            working = false;
            return null;
        }
        double x = ctx.player().getX(), z = ctx.player().getZ();
        List<Cell> ahead = advanceCursor(x, z);
        if (ahead.isEmpty()) {
            // The band's end: start on what is left, unless some of this band is still in reach.
            order = List.of();
            remaining = remaining();
            if (remaining.isEmpty()) {
                working = false;
                return null;
            }
            ahead = advanceCursor(x, z);
            if (ahead.isEmpty()) ahead = remaining;
        }
        Cell first = ahead.getFirst();
        if (!first.equals(head)) {
            head = first;
            headTicks = 0;
        } else if (++headTicks > HEAD_TICKS) {
            // Attack has been held at it this long, or nothing could see it.
            skipped.add(head);
            order = List.of();
            head = null;
            return pause();
        }
        double distance = Math.hypot(first.centerX() - x, first.centerZ() - z);
        if (fallbackTicks > 0) fallbackTicks--;
        if (distance > CLOSE_RANGE || fallbackTicks > 0 || pathing && !safeToCancel) {
            pathing = true;
            working = distance <= CLOSE_RANGE;
            return new PathingCommand(new GoalNear(target(), 3), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        pathing = false;
        working = true;
        double speed = ctx.player().getSpeed() / (ctx.player().isSprinting() ? 1.3 : 1);
        double timeScale = Math.max(.5, Math.min(1, NORMAL_WALK_SPEED / speed));
        aim(remaining, first, x, z, timeScale);
        move(ahead, x, z, speed);
        trackStuck(x, z);
        return pause();
    }

    private void start() {
        random = new ForkableRandom(Baritone.settings().naturalMovementSeed.value ^ 0x7c3b19e5a2d04f67L);
        plan = SandSweepModel.plan(random.nextDouble());
        var motor = NaturalSteering.parameters(Baritone.settings().naturalMovementSeed.value,
                Baritone.settings().smoothMovementMaxTurn.value, Baritone.settings().smoothMovementAcceleration.value);
        motorLag = motor.damping() / motor.stiffness();
        startDelay = 2 + (int) (random.nextDouble() * 4);
        side = random.nextDouble() < .5 ? 1 : -1;
        forwardDown = true;
        lastX = ctx.player().getX();
        lastZ = ctx.player().getZ();
    }

    /** Every exposed cell near the player; cheap enough at this rate, and it picks up sand that grows back. */
    private void scan() {
        live.removeIf(cell -> !alive(cell));
        if (scanIn-- > 0) return;
        scanIn = SCAN_TICKS;
        BlockPos feet = ctx.playerFeet();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = -SCAN_HEIGHT; dy <= SCAN_HEIGHT; dy++) {
                    pos.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (wanted.test(ctx.world().getBlockState(pos)) && exposed(pos)) live.add(new Cell(pos.getX(), pos.getY(), pos.getZ()));
                }
            }
        }
    }

    private boolean alive(Cell cell) {
        return wanted.test(ctx.world().getBlockState(new BlockPos(cell.x(), cell.y(), cell.z())));
    }

    private boolean exposed(BlockPos pos) {
        if (ctx.world().getBlockState(pos.above()).isAir()) return true;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (ctx.world().getBlockState(pos.relative(direction)).isAir()) return true;
        }
        return false;
    }

    /** @return the live cells at and after the cursor, which moves on unless too many in reach are still behind it */
    private List<Cell> advanceCursor(double x, double z) {
        if (mopping) return order.stream().filter(cell -> live.contains(cell) && !skipped.contains(cell)).toList();
        int behind = 0;
        int whole = Math.min((int) cursor, order.size());
        for (Cell cell : order.subList(0, whole)) {
            if (live.contains(cell) && !skipped.contains(cell) && Math.hypot(cell.centerX() - x, cell.centerZ() - z) <= REACH_RANGE) behind++;
        }
        if (behind <= MAX_BEHIND || ++cursorWait > CURSOR_WAIT_TICKS) {
            cursor = Math.min(order.size(), cursor + CURSOR_PER_TICK);
            cursorWait = 0;
        }
        List<Cell> ahead = new ArrayList<>();
        for (Cell cell : order.subList(Math.min((int) cursor, order.size()), order.size())) {
            if (live.contains(cell) && !skipped.contains(cell)) ahead.add(cell);
        }
        // Finish what is in reach before leaving the band.
        if (ahead.isEmpty() && behind > 0 && cursorWait <= CURSOR_WAIT_TICKS) {
            for (Cell cell : order) {
                if (live.contains(cell) && !skipped.contains(cell) && Math.hypot(cell.centerX() - x, cell.centerZ() - z) <= REACH_RANGE) ahead.add(cell);
            }
            cursorWait++;
        }
        return ahead;
    }

    private List<Cell> remaining() {
        if (!mopping && fieldSize > 0 && live.size() <= Math.max(MOP_UP_MIN, fieldSize * MOP_UP_SHARE)) order = List.of();
        List<Cell> remaining = order.stream().filter(cell -> live.contains(cell) && !skipped.contains(cell)).toList();
        if (!remaining.isEmpty()) return remaining;
        List<Cell> open = live.stream().filter(cell -> !skipped.contains(cell)).toList();
        if (open.isEmpty() && !live.isEmpty() && retries++ < RETRIES) {
            // What was put off gets another go before the job calls it unreachable.
            skipped.clear();
            open = List.copyOf(live);
        }
        if (open.isEmpty()) return List.of();
        double x = ctx.player().getX(), z = ctx.player().getZ();
        fieldSize = Math.max(fieldSize, open.size());
        mopping = open.size() <= Math.max(MOP_UP_MIN, fieldSize * MOP_UP_SHARE);
        List<Cell> field = mopping ? open : SandSweepModel.nearestField(open, x, z);
        int band = 4 + (random.nextDouble() < .35 ? 1 : 0);
        order = mopping ? SandSweepModel.tour(open, x, z) : SandSweepModel.order(field, x, z, plan, band);
        cursor = 0;
        cursorWait = 0;
        standoff = mopping ? MOP_UP_STANDOFF : 2.7 + random.nextDouble() * .6;
        bandX = order.stream().mapToDouble(Cell::centerX).average().orElseThrow();
        bandZ = order.stream().mapToDouble(Cell::centerZ).average().orElseThrow();
        bands++;
        // From the whole field, once: what is left over later may all lie on a dune.
        int[] heights = field.stream().mapToInt(Cell::y).sorted().toArray();
        int highest = heights[heights.length / 2] + 2;
        if (highestFeet == 0 || Math.abs(highest - highestFeet) > 2) highestFeet = highest;
        Cell first = order.getFirst();
        MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(new BlockPos(first.x(), first.y(), first.z())));
        return order;
    }

    /** Sends the camera to its stroke's end, which the look motor shapes; toward the sand when none is in reach. */
    private void aim(List<Cell> remaining, Cell first, double x, double z, double timeScale) {
        Rotation view = ctx.playerRotations();
        if (stroking) {
            strokeAge++;
            if (strokeCell != null && alive(strokeCell)) {
                Vec3 point = aimPoint(strokeCell);
                if (point != null) {
                    Rotation end = rotationTo(point);
                    toYaw = end.getYaw();
                    toPitch = end.getPitch();
                }
            }
            double yawLeft = Math.abs(Mth.wrapDegrees(toYaw + overshoot * side - view.getYaw())), pitchLeft = Math.abs(toPitch - view.getPitch());
            if (yawLeft <= ARRIVED_YAW && pitchLeft <= ARRIVED_PITCH || strokeAge >= strokeTicks * STROKE_TIME_MARGIN) stroking = false;
        }
        if (!stroking) beginStroke(remaining, view, timeScale);
        noView = !stroking;
        if (!stroking) {
            float bearing = (float) Math.toDegrees(Math.atan2(-(first.centerX() - x), first.centerZ() - z));
            baritone.getLookBehavior().updateTarget(new Rotation(bearing, (float) TRAVEL_PITCH), false);
            return;
        }
        double yawLeft = Mth.wrapDegrees(toYaw + overshoot * side - view.getYaw()), pitchLeft = toPitch - view.getPitch();
        // The motor settles at stiffness / damping of the error per tick, and that differs from job to job.
        double lead = strokeSpeed * motorLag;
        baritone.getLookBehavior().updateTarget(new Rotation(
                (float) (view.getYaw() + Math.max(-lead, Math.min(lead, yawLeft))),
                (float) (view.getPitch() + pitchLeft * motorLag * PITCH_RESPONSE)), false);
    }

    private void beginStroke(List<Cell> remaining, Rotation view, double timeScale) {
        List<Candidate> candidates = new ArrayList<>();
        List<Cell> cells = new ArrayList<>();
        Vec3 eyes = ctx.player().getEyePosition(1.0F);
        Set<Long> columns = new HashSet<>();
        for (Cell cell : live) columns.add(SandSweepModel.column(cell.x(), cell.z()));
        double ground = ctx.playerFeet().getY();
        for (Cell cell : remaining) {
            if (cells.size() >= CANDIDATES) break;
            Vec3 point = aimPoint(cell);
            if (point == null) continue;
            Rotation rotation = RotationUtils.calcRotationFromVec3d(eyes, point, view);
            double yawOffset = Mth.wrapDegrees(rotation.getYaw() - view.getYaw()), pitchOffset = rotation.getPitch() - view.getPitch();
            double rank = mopping ? cells.size() : SandSweepModel.orderFromCursor(order.indexOf(cell), (int) cursor);
            int crossed = SandSweepModel.crossed(eyes.x, eyes.y, eyes.z, view.getYaw(), view.getPitch(), yawOffset, pitchOffset, columns, ground);
            candidates.add(new Candidate(yawOffset, pitchOffset, rank, crossed, Math.hypot(cell.centerX() - eyes.x, cell.centerZ() - eyes.z)));
            cells.add(cell);
        }
        if (cells.isEmpty()) return;
        double amplitude = SandSweepModel.strokeAmplitude(random);
        int wantedSide = -side;
        // A cell passed over again and again gets the camera to itself.
        boolean forced = headTicks > (mopping ? MOP_UP_FORCE_TICKS : FORCE_HEAD_TICKS) && cells.getFirst().equals(remaining.getFirst());
        boolean leaving = !mopping && candidates.getFirst().range() > LEAVING_RANGE && order.indexOf(cells.getFirst()) < (int) cursor;
        pitchDirection = -pitchDirection;
        double pitchChange = SandSweepModel.pitchChange(pitchDirection, random);
        int choice = forced || leaving ? 0 : SandSweepModel.strokeEnd(candidates, wantedSide, amplitude, pitchChange);
        if (choice < 0) {
            wantedSide = side;
            choice = SandSweepModel.strokeEnd(candidates, wantedSide, amplitude, pitchChange);
        }
        boolean narrow = choice < 0 || forced;
        if (choice < 0) choice = SandSweepModel.nearestToView(candidates);
        side = wantedSide;
        Candidate end = candidates.get(choice);
        strokeCell = cells.get(choice);
        toYaw = view.getYaw() + end.yawOffset();
        toPitch = view.getPitch() + end.pitchOffset();
        overshoot = narrow ? 0 : 1 + random.nextDouble() * 6;
        strokeSpeed = SandSweepModel.strokeSpeed(random);
        strokeTicks = SandSweepModel.strokeTicks(end.yawOffset(), end.pitchOffset(), random, timeScale);
        strokeAge = 0;
        stroking = true;
        keyHold = 0;
        switchTicks = random.nextDouble() < .3 ? 1 + (int) (random.nextDouble() * 2) : 0;
    }

    private Rotation rotationTo(Vec3 point) {
        return RotationUtils.calcRotationFromVec3d(ctx.player().getEyePosition(1.0F), point, ctx.playerRotations());
    }

    /** The middle of a face of this cell the crosshair can reach, the top if it can. */
    private Vec3 aimPoint(Cell cell) {
        Vec3 eyes = ctx.player().getEyePosition(1.0F);
        double reach = Math.min(CELL_REACH, ctx.playerController().getBlockReachDistance() - .1);
        BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
        if (Math.hypot(cell.centerX() - eyes.x, cell.centerZ() - eyes.z) < MIN_RANGE) return null;
        Vec3 top = new Vec3(cell.centerX(), cell.y() + 1, cell.centerZ());
        if (eyes.distanceTo(top) <= reach && eyes.y > top.y && clearView(eyes, top, pos)) return top;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            Vec3 face = Vec3.atCenterOf(pos).add(direction.getStepX() * .5, 0, direction.getStepZ() * .5);
            if (eyes.distanceTo(face) <= reach && clearView(eyes, face, pos)) return face;
        }
        return null;
    }

    private boolean clearView(Vec3 eyes, Vec3 point, BlockPos pos) {
        Vec3 inside = point.add(point.subtract(eyes).normalize().scale(.05));
        BlockHitResult hit = ctx.world().clip(new ClipContext(eyes, inside, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    /** Walks toward a spot a little back from the patch being worked, on the side the body is already on. */
    private void move(List<Cell> remaining, double x, double z, double speed) {
        int count = mopping ? 1 : Math.min(PATCH, remaining.size());
        double patchX = 0, patchZ = 0, weights = 0;
        for (int index = 0; index < count; index++) {
            // Cells further along count for less, so the body keeps up with what is being worked now.
            double weight = 1 / (1 + index / 6.0);
            patchX += remaining.get(index).centerX() * weight;
            patchZ += remaining.get(index).centerZ() * weight;
            weights += weight;
        }
        patchX /= weights;
        patchZ /= weights;
        double awayX = x - patchX, awayZ = z - patchZ, away = Math.hypot(awayX, awayZ);
        if (away < .01) {
            awayX = 1;
            away = 1;
        }
        awayX /= away;
        awayZ /= away;
        double outwardX = patchX - bandX, outwardZ = patchZ - bandZ, outward = Math.hypot(outwardX, outwardZ);
        if (plan != Plan.STRIPS && !mopping && outward > .01) {
            awayX += outwardX / outward * OUTWARD_BIAS;
            awayZ += outwardZ / outward * OUTWARD_BIAS;
            away = Math.hypot(awayX, awayZ);
            if (away > .01) {
                awayX /= away;
                awayZ /= away;
            }
        }
        double goalX = patchX + awayX * standoff, goalZ = patchZ + awayZ * standoff;
        for (Cell cell : live) {
            double dx = x - cell.centerX(), dz = z - cell.centerZ(), range = Math.hypot(dx, dz);
            if (range >= KEEP_AWAY || range < .01) continue;
            goalX += dx / range * (KEEP_AWAY - range) * KEEP_AWAY_GAIN;
            goalZ += dz / range * (KEEP_AWAY - range) * KEEP_AWAY_GAIN;
        }
        Cell first = remaining.getFirst();
        double headX = goalX - first.centerX(), headZ = goalZ - first.centerZ(), head = Math.hypot(headX, headZ);
        double limit = noView ? NO_VIEW_STANDOFF : HEAD_REACH;
        if (head > limit) {
            goalX = first.centerX() + headX / head * limit;
            goalZ = first.centerZ() + headZ / head * limit;
        }
        // Out of reach of the next cell, the only way may be across live sand.
        boolean keepOffSand = !noView && Math.hypot(first.centerX() - x, first.centerZ() - z) <= HEAD_REACH + .4;
        double goalDistance = Math.hypot(goalX - x, goalZ - z);
        double yaw = ctx.player().getYRot();
        double bearing = Math.toDegrees(Math.atan2(-(goalX - x), goalZ - z));
        double offset = Mth.wrapDegrees(bearing - yaw);
        double urgency = Math.min(1, goalDistance / 1.5);

        if (startDelay > 0) {
            startDelay--;
            apply(Keys.NONE, speed);
            return;
        }
        if (--tapTicks <= 0) {
            forwardDown = !forwardDown;
            tapTicks = CropSweepModel.forwardTapTicks(forwardDown, random);
        }
        Move wanted = new Move(new BlockStateInterface(ctx), new HashMap<>(), liveAt(ctx.playerFeet().getY()), keepOffSand, offset, goalDistance, speed);
        jumpWanted = false;
        if (keyHold-- > 0 && clear(keys, wanted)) {
            apply(keys, speed);
            return;
        }
        keyHold = (int) (random.nextDouble() * 3);
        if (switchTicks > 0) {
            switchTicks--;
            Keys forward = new Keys(true, false, false, false);
            jumpWanted = false;
            if (clear(forward, wanted)) {
                keys = forward;
                keyHold = 0;
                apply(keys, speed);
                return;
            }
        }
        keys = Keys.NONE;
        List<Keys> ranked = SandSweepModel.rankKeys(offset, urgency, side, side > 0 || forwardDown, plan == Plan.RIM_RIGHT ? -1 : 1);
        for (Move move : List.of(wanted, wanted.acrossSand())) {
            for (Keys option : ranked) {
                jumpWanted = false;
                if (clear(option, move)) {
                    keys = option;
                    apply(keys, speed);
                    return;
                }
            }
        }
        jumpWanted = false;
        apply(keys, speed);
    }

    private record Move(BlockStateInterface bsi, Map<Long, Boolean> cache, Set<Long> live, boolean keepOffSand, double goalOffset, double goalDistance, double speed) {
        /** Better a step onto live sand than standing still. */
        Move acrossSand() {
            return new Move(bsi, cache, live, false, goalOffset, goalDistance, speed);
        }
    }

    /** Live sand a body at this height would stand on or walk into. */
    private Set<Long> liveAt(int feet) {
        Set<Long> cells = new HashSet<>();
        for (Cell cell : live) {
            if (cell.y() == feet - 1 || cell.y() == feet) cells.add(SandSweepModel.column(cell.x(), cell.z()));
        }
        return cells;
    }

    /** Follows this input a few ticks ahead with vanilla ground physics, checking the body's corners. A low step is fine with a jump, if the body means to go that way. */
    private boolean clear(Keys input, Move move) {
        if (!ctx.player().onGround()) return true;
        double yaw = Math.toRadians(ctx.player().getYRot());
        double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw), rightX = -Math.cos(yaw), rightZ = -Math.sin(yaw);
        double along = (input.forward() ? 1 : 0) - (input.back() ? 1 : 0), across = (input.right() ? 1 : 0) - (input.left() ? 1 : 0);
        double inputX = forwardX * along + rightX * across, inputZ = forwardZ * along + rightZ * across;
        double length = Math.hypot(inputX, inputZ);
        if (length > 1e-6) {
            inputX /= length;
            inputZ /= length;
        }
        double acceleration = move.speed * (input.forward() && move.speed < SPRINT_LIMIT ? 1.3 : 1);
        Vec3 motion = ctx.player().getDeltaMovement();
        double vx = motion.x, vz = motion.z, x = ctx.player().getX(), z = ctx.player().getZ();
        BlockPos standing = ctx.playerFeet();
        int feet = standing.getY();
        boolean onSand = move.live.contains(SandSweepModel.column(standing.getX(), standing.getZ()));
        boolean climbing = move.goalDistance > CLIMB_DISTANCE && input != Keys.NONE
                && Math.abs(Mth.wrapDegrees(input.angle() - move.goalOffset)) <= CLIMB_ARC;
        for (int tick = 0; tick < PREDICTION_TICKS; tick++) {
            vx += inputX * acceleration;
            vz += inputZ * acceleration;
            x += vx;
            z += vz;
            vx *= .546;
            vz *= .546;
            for (double cornerX : CORNERS) {
                for (double cornerZ : CORNERS) {
                    int cellX = Mth.floor(x + cornerX), cellZ = Mth.floor(z + cornerZ);
                    if (move.keepOffSand && !onSand && tick < LIVE_PREDICTION_TICKS && move.live.contains(SandSweepModel.column(cellX, cellZ))) return false;
                    if (open(move.bsi, cellX, feet, cellZ, move.cache)) {
                        if (!floorBelow(move.bsi, cellX, feet, cellZ)) return false;
                    } else {
                        if (!climbing || feet + 1 > highestFeet || !open(move.bsi, cellX, feet + 1, cellZ, move.cache)
                                || !open(move.bsi, standing.getX(), feet + 1, standing.getZ(), move.cache)) return false;
                        if (tick <= 2) jumpWanted = true;
                    }
                }
            }
        }
        return true;
    }

    private boolean open(BlockStateInterface bsi, int x, int y, int z, Map<Long, Boolean> cache) {
        return cache.computeIfAbsent(BlockPos.asLong(x, y, z), key -> MovementHelper.canWalkThrough(bsi, x, y, z) && MovementHelper.canWalkThrough(bsi, x, y + 1, z));
    }

    /** Stepping down a little is fine; dropping further is not. */
    private static boolean floorBelow(BlockStateInterface bsi, int x, int feet, int z) {
        for (int y = feet - 1; y >= feet - 3; y--) {
            if (MovementHelper.canWalkOn(bsi, x, y, z)) return true;
            if (!MovementHelper.canWalkThrough(bsi, x, y, z)) return false;
        }
        return false;
    }

    private void apply(Keys input, double speed) {
        var override = baritone.getInputOverrideHandler();
        override.setInputForceState(Input.MOVE_FORWARD, input.forward());
        override.setInputForceState(Input.MOVE_BACK, input.back());
        override.setInputForceState(Input.MOVE_LEFT, input.left());
        override.setInputForceState(Input.MOVE_RIGHT, input.right());
        override.setInputForceState(Input.SPRINT, input.forward() && speed < SPRINT_LIMIT);
        override.setInputForceState(Input.JUMP, jumpWanted && ctx.player().onGround());
    }

    private void trackStuck(double x, double z) {
        boolean moved = Math.hypot(x - lastX, z - lastZ) > .02;
        lastX = x;
        lastZ = z;
        stuckTicks = moved || startDelay > 0 ? 0 : stuckTicks + 1;
        if (stuckTicks < STUCK_TICKS) return;
        // Let the pathfinder walk out, then carry on from wherever that leaves the player.
        stuckTicks = 0;
        fallbackTicks = FALLBACK_TICKS;
        stroking = false;
    }

    private static PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }
}
