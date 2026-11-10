/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.behavior;

import baritone.Baritone;
import baritone.behavior.look.NaturalAim;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.look.ForkableRandom;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.pathing.movement.movements.MovementDiagonal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/** Optional sprint hops with a checked landing runway and a varied, reproducible cadence. */
public final class NaturalMovementBehavior extends Behavior {
    private boolean hopping, leftGround;
    private int floorY, hopStarted, nextJump;
    private long seed = Long.MIN_VALUE;
    private ForkableRandom random = new ForkableRandom(0);
    private String decision = "idle";
    private int jumps, startedAt;
    private ForkableRandom gazeRandom = new ForkableRandom(0);
    private float walkingPitch = 5;
    private int nextGaze;
    private int lookahead;
    private double steeringX, steeringZ;
    private boolean flicking;
    private double flickStart, flickEnd, holdYaw;
    private int flickTick, flickDuration, scriptedTick = Integer.MIN_VALUE;
    private ForkableRandom flickRandom = new ForkableRandom(0);
    private int sideKey; // -1 A, 0 none, 1 D

    public NaturalMovementBehavior(Baritone baritone) { super(baritone); }

    public void reset() {
        hopping = leftGround = false;
        seed = Long.MIN_VALUE;
        nextJump = 0;
        jumps = 0;
        startedAt = ctx.player() == null ? 0 : ctx.player().tickCount;
        decision = "idle";
        walkingPitch = 5;
        lookahead = 0;
        flicking = false;
        sideKey = 0;
    }

    /**
     * Where the camera should point this tick while walking towards {@code wanted}. Recorded players hold the mouse
     * dead still and correct in short flicks (about 1.4 a second, median ~10 degrees in ~6 ticks, 40 degrees in ~14):
     * once the heading is off by a few degrees (varies per job), sweep a smooth minimum-jerk stroke to a spot near it,
     * a little short or long like a hand, then hold exactly there. LookBehavior applies these yaws as they are.
     */
    public float cameraYaw(float actual, float wanted) {
        int tick = ctx.player().tickCount;
        if (scriptedTick != tick - 1 && scriptedTick != tick) { // resume after other steps moved the camera
            flicking = false;
            holdYaw = actual;
        }
        scriptedTick = tick;
        if (flicking) {
            flickTick++;
            if (flickTick < flickDuration) {
                double t = flickTick / (double) flickDuration;
                return (float) (flickStart + (flickEnd - flickStart) * t * t * t * (10 - 15 * t + 6 * t * t));
            }
            flicking = false;
            holdYaw = flickEnd;
        }
        double error = net.minecraft.util.Mth.wrapDegrees(wanted - holdYaw);
        double band = 4 + 4 * jobFraction(0x5ca11L);
        if (Math.abs(error) > band) {
            flickStart = holdYaw;
            flickEnd = holdYaw + error * (.85 + .3 * flickRandom.nextDouble());
            double amplitude = Math.abs(flickEnd - flickStart);
            flickDuration = (int) Math.round(Math.min(20, (3.5 + .25 * amplitude) * (.8 + .4 * flickRandom.nextDouble())));
            flickDuration = Math.max(3, flickDuration);
            flicking = true;
            flickTick = 0;
            return cameraYaw(actual, wanted);
        }
        return (float) holdYaw;
    }

    /** Whether {@link #cameraYaw} produced the walking camera's yaw this tick (applied exactly, without the motor). */
    public boolean scriptedCameraThisTick() {
        return ctx.player() != null && scriptedTick == ctx.player().tickCount;
    }

    /** A/D while the camera holds: -1 left, 1 right, 0 neither. Pressed past a small error, released once it's gone. */
    public int sideKey(float actual, float wanted) {
        double error = net.minecraft.util.Mth.wrapDegrees(wanted - actual); // > 0: wanted is to the right
        double press = 2.5 + 2.5 * jobFraction(0x51deL);
        if (flicking) sideKey = 0;
        else if (sideKey != 0 && (Math.abs(error) < 1.5 || Math.signum(error) != sideKey)) sideKey = 0;
        else if (sideKey == 0 && Math.abs(error) > press) sideKey = error > 0 ? 1 : -1;
        return sideKey;
    }

    private double jobFraction(long salt) {
        return new ForkableRandom(Baritone.settings().naturalMovementSeed.value ^ salt).nextDouble();
    }

    public int elapsedTicks() { return ctx.player() == null ? 0 : ctx.player().tickCount - startedAt; }

    public float walkingPitch() { return walkingPitch; }

    public void observeSteering(int steps, double x, double z) { lookahead = steps; steeringX = x; steeringZ = z; }
    public int lookaheadSteps() { return lookahead; }

    public java.util.Map<String, Object> status() { return java.util.Map.of("decision", decision, "jumps", jumps, "hopping", hopping,
            "lookaheadSteps", lookahead, "steeringX", steeringX, "steeringZ", steeringZ); }

    /** Elevated base speed needs an ordinary walking pace near turns and stops. */
    public boolean shouldSneakForPrecision() {
        var player = ctx.player();
        if (!Baritone.settings().naturalMovement.value || player == null || player.getSpeed() <= .18
                || player.isInWater() || player.isInLava() || player.isFallFlying() || player.isPassenger()
                || !baritone.getPathingBehavior().isPathing()) return false;
        var heading = baritone.getLookBehavior().requestedRotation();
        if (heading.isPresent() && Math.abs(Rotation.normalizeYaw(heading.get().getYaw() - player.getYRot())) > 15) return true;
        var executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null) return false;
        var movements = executor.getPath().movements();
        int index = executor.getPosition();
        if (index < 0 || index >= movements.size()) return false;
        var current = movements.get(index);
        if (!(current instanceof MovementTraverse || current instanceof MovementDiagonal)
                || current.getDirection().getY() != 0) return true;
        var end = current.getDest();
        for (int i = index + 1; i < movements.size() && i <= index + 7; i++) {
            var next = movements.get(i);
            if (!MovementHelper.naturalLookaheadSafe(ctx, next) || !next.getDirection().equals(current.getDirection())) break;
            end = next.getDest();
        }
        // Begin slowing before the remaining momentum can carry us through the
        // corner. Once crouched, the minimum distance keeps the decision stable.
        double approach = Math.max(3.5, player.getDeltaMovement().horizontalDistance() * 5);
        return Math.hypot(end.x + .5 - player.getX(), end.z + .5 - player.getZ()) < approach;
    }

    @Override public void onWorldEvent(WorldEvent event) { reset(); }

    public BetterBlockPos projectFeet(BetterBlockPos actual) {
        if (hopping && ctx.player() != null && !ctx.player().onGround()
                && ctx.player().tickCount - hopStarted < 30 && ctx.player().getY() >= floorY - 0.05
                && ctx.player().getY() <= floorY + 1.5) {
            return new BetterBlockPos(actual.x, floorY, actual.z);
        }
        return actual;
    }

    @Override public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) { reset(); return; }
        var player = ctx.player();
        decision = "waiting for sprint";
        if (!Baritone.settings().naturalMovement.value) {
            hopping = false;
            decision = "disabled";
            return;
        }
        if (seed != Baritone.settings().naturalMovementSeed.value) {
            seed = Baritone.settings().naturalMovementSeed.value;
            random = new ForkableRandom(seed ^ 0x52ca89e7L);
            gazeRandom = new ForkableRandom(seed ^ 0x789e38abL);
            flickRandom = new ForkableRandom(seed ^ 0x3f1c4b2dL);
            walkingPitch = NaturalAim.walkingPitch(seed);
            nextGaze = player.tickCount + 55 + (int) (gazeRandom.nextDouble() * 90);
            nextJump = player.tickCount + 5 + (int) (random.nextDouble() * 8);
        }
        if (baritone.getPathingBehavior().isPathing() && player.tickCount >= nextGaze) {
            double choice = gazeRandom.nextDouble();
            walkingPitch = (float) (choice < .2 ? -1 + gazeRandom.nextDouble() * 4
                    : choice < .8 ? NaturalAim.walkingPitch(seed) + (gazeRandom.nextDouble()-.5)*4 : 9 + gazeRandom.nextDouble() * 4);
            nextGaze = player.tickCount + 55 + (int) (gazeRandom.nextDouble() * 125);
        }
        if (!Baritone.settings().naturalSprintJump.value) { hopping = false; decision = "disabled"; return; }
        if (hopping) {
            decision = "hop in progress";
            if (!player.onGround()) leftGround = true;
            if (player.tickCount - hopStarted > 30 || leftGround && player.onGround()) {
                hopping = false;
                // Recorded players chain hops by keeping space down: the next jump leaves on the landing tick, so the
                // ground's friction never eats the speed (64% of their hops follow straight on). Now and then a tick
                // later, sometimes a short break.
                double pause = random.nextDouble();
                nextJump = player.tickCount + (pause < 0.75 ? 0 : pause < 0.9 ? 1 : 3 + (int) (random.nextDouble() * 6));
            }
            if (hopping || player.tickCount < nextJump) return;
        }
        if (player.tickCount < nextJump || !player.onGround() || !player.isSprinting()
                || player.getDeltaMovement().horizontalDistanceSqr() < 0.015 || player.getFoodData().getFoodLevel() <= 10
                || player.isInWater() || player.isInLava() || player.isCrouching() || player.isPassenger()
                || !player.getActiveEffects().isEmpty() || player.horizontalCollision) return;
        if (!sprintJumpGainsSpeed(player.getSpeed())) { decision = "grounded sprint is faster"; return; }
        var input = baritone.getInputOverrideHandler();
        if (!input.isInputForcedDown(Input.MOVE_FORWARD) || input.isInputForcedDown(Input.SNEAK)
                || input.isInputForcedDown(Input.CLICK_LEFT) || input.isInputForcedDown(Input.CLICK_RIGHT)) return;
        var executor = baritone.getPathingBehavior().getCurrent();
        decision = "no path";
        if (executor == null || !baritone.getPathingBehavior().isPathing()) return;
        var movements = executor.getPath().movements();
        int index = executor.getPosition();
        decision = "short runway or nonflat movement";
        if (index < 0 || index >= movements.size()) return;
        var current = movements.get(index);
        if (!(current instanceof MovementTraverse || current instanceof MovementDiagonal)
                || !MovementHelper.naturalLookaheadSafe(ctx, current)) return;
        if (current.getDirection().getY() != 0) return;
        int ground = current.getSrc().y;
        // The path keeps going for a few blocks: straight and diagonal steps (a road at an angle mixes them) and
        // single steps up or down, which a sprint jump clears like people do on the hub's slopes ...
        java.util.Set<Long> cells = new java.util.HashSet<>();
        double covered = 0;
        for (int i = index; covered < 5; i++) {
            if (i >= movements.size()) return;
            var next = movements.get(i);
            boolean flat = (next instanceof MovementTraverse || next instanceof MovementDiagonal)
                    && MovementHelper.naturalLookaheadSafe(ctx, next);
            boolean step = (next instanceof baritone.pathing.movement.movements.MovementAscend
                    || next instanceof baritone.pathing.movement.movements.MovementDescend)
                    && Math.abs(next.getDirection().getY()) == 1;
            if (!(flat || step && i > index) || Math.abs(next.getDest().y - ground) > 1) return;
            cells.add(MovementHelper.columnKey(next.getSrc()));
            cells.add(MovementHelper.columnKey(next.getDest()));
            covered += Math.hypot(next.getDirection().getX(), next.getDirection().getZ());
        }
        // ... and the flight is over open ground (people hop wherever the ground ahead is clear, not only along the
        // path's blocks): along the facing (the jump boost goes that way) and the motion, about one sprint jump
        // (12 ticks) far, the whole body over solid floor at most one block up or down (up only from 1.5 blocks
        // out, once the jump is high enough) with the body's room above it, landing next to the path.
        decision = "runway obstacle, turn or edge";
        var feet = player.position();
        double yaw = Math.toRadians(player.getYRot());
        var motion = player.getDeltaMovement();
        double speed = Math.hypot(motion.x, motion.z);
        double[][] lines = {{-Math.sin(yaw), Math.cos(yaw)}, {motion.x / speed, motion.z / speed}};
        for (double[] line : lines) {
            for (double d = .25; d <= 4.5; d += .25) {
                double x = feet.x + line[0] * d, z = feet.z + line[1] * d;
                for (double ox : new double[]{-.3, .3}) for (double oz : new double[]{-.3, .3}) {
                    BlockPos cell = BlockPos.containing(x + ox, ground, z + oz);
                    BlockPos floor = d >= 1.5 && hopFloorSupported(cell.above()) ? cell.above()
                            : hopFloorSupported(cell) ? cell : hopFloorSupported(cell.below()) ? cell.below() : null;
                    if (floor == null) { decision = "flight: no floor " + (line == lines[0] ? "ahead" : "along motion"); return; }
                    // the body's two blocks; a ceiling above that only cuts the jump short (people hop under them too)
                    for (int h = 0; h <= 1; h++) if (!MovementHelper.fullyPassable(ctx, floor.above(h)) && !(h == 0 && lowFloor(floor))) { decision = "flight: body blocked by " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(ctx.world().getBlockState(floor.above(h)).getBlock()).getPath(); return; }
                    if (floor.getY() < ground && !MovementHelper.fullyPassable(ctx, cell)) { decision = "flight: body blocked"; return; }
                }
            }
            double landX = feet.x + line[0] * 4.5, landZ = feet.z + line[1] * 4.5;
            boolean nearPath = false;
            for (long key : cells) {
                BlockPos column = BlockPos.of(key);
                if (Math.hypot(column.getX() + .5 - landX, column.getZ() + .5 - landZ) <= 1.2) nearPath = true;
            }
            if (!nearPath) { decision = "flight: lands off the path"; return; }
        }
        decision = "turning";
        double cos = lines[0][0] * lines[1][0] + lines[0][1] * lines[1][1];
        if (Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos)))) > 35) return;
        floorY = ground;
        hopStarted = player.tickCount;
        hopping = true;
        decision = "jump";
        jumps++;
        leftGround = false;
        input.setInputForceState(Input.JUMP, true);
    }

    /** A bottom slab or similar under half a block: standing on it, it is the floor, not an obstacle. */
    private boolean lowFloor(BlockPos pos) {
        var shape = ctx.world().getBlockState(pos).getCollisionShape(ctx.world(), pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= .5;
    }

    /** Something to land on below {@code pos}: anything walkable (slabs, paths, farmland, stairs too), not slippery. */
    private boolean hopFloorSupported(BlockPos pos) {
        var floor = ctx.world().getBlockState(pos.below());
        if (!MovementHelper.canWalkOn(ctx, pos.below()) || MovementHelper.avoidWalkingInto(floor)
                || floor.is(Blocks.ICE) || floor.is(Blocks.PACKED_ICE) || floor.is(Blocks.BLUE_ICE)
                || floor.is(Blocks.HONEY_BLOCK) || floor.is(Blocks.SLIME_BLOCK)) return false;
        return true;
    }

    private static boolean sprintJumpGainsSpeed(double acceleration) {
        // Ordinary solid-floor friction, sprint jump impulse and air input.
        // Base-speed boosts improve grounded acceleration without improving air
        // input equally. Estimate one twelve-tick flight before adding a hop.
        double grounded = acceleration / (1 - .546);
        double velocity = grounded * .546 + .2;
        double distance = 0;
        for (int tick = 0; tick < 12; tick++) {
            velocity += .026;
            distance += velocity;
            velocity *= .91;
        }
        return distance / 12 > grounded * 1.05;
    }
}
