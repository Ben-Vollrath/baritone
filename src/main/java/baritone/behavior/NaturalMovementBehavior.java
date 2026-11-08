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
                nextJump = player.tickCount + (random.nextDouble() < 0.90 ? (int) (random.nextDouble() * 3) : 4 + (int) (random.nextDouble() * 7));
            }
            return;
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
        var direction = current.getDirection();
        if (direction.getY() != 0) return;
        // Count distance rather than nodes: a diagonal step is sqrt(2) blocks.
        int runwaySteps = (int) Math.ceil(7 / Math.hypot(direction.getX(), direction.getZ()));
        if (index + runwaySteps > movements.size()) return;
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        decision = "turning";
        if (Math.abs(Rotation.normalizeYaw(desiredYaw - player.getYRot())) > 6) return;
        int ground = current.getSrc().y;
        double length = Math.hypot(direction.getX(), direction.getZ());
        double sideX = -direction.getZ() / length, sideZ = direction.getX() / length;
        double lateral = (player.getX() - current.getSrc().x - .5) * sideX
                + (player.getZ() - current.getSrc().z - .5) * sideZ;
        double yaw = Math.toRadians(player.getYRot());
        double sideVelocity = player.getDeltaMovement().x * sideX + player.getDeltaMovement().z * sideZ
                + .2 * (-Math.sin(yaw) * sideX + Math.cos(yaw) * sideZ);
        double landingLateral = lateral + sideVelocity * 12;
        decision = "runway obstacle, turn or edge";
        for (int i = index; i < index + runwaySteps; i++) {
            var next = movements.get(i);
            if (!(next instanceof MovementTraverse || next instanceof MovementDiagonal)
                    || !MovementHelper.naturalLookaheadSafe(ctx, next)
                    || !next.getDirection().equals(direction) || next.getDest().y != ground) return;
            // Keep a broad supported floor, but check air against the swept body.
            // A wall beside a supported path need not occupy the player's flight.
            for (int side = -1; side <= 1; side++) {
                int ox = -direction.getZ() * side, oz = direction.getX() * side;
                if (!hopFloorSupported(next.getSrc().offset(ox, 0, oz)) || !hopFloorSupported(next.getDest().offset(ox, 0, oz))) return;
                if (next instanceof MovementDiagonal) {
                    // Check support between diagonal nodes, including both corners
                    // rather than only the diagonal centers.
                    if (!hopFloorSupported(new BlockPos(next.getSrc().x + ox, ground, next.getDest().z + oz))
                            || !hopFloorSupported(new BlockPos(next.getDest().x + ox, ground, next.getSrc().z + oz))) return;
                }
            }
            if (!hopBodyClear(next.getSrc(), next.getDest(), sideX, sideZ, lateral, landingLateral)) return;
        }
        floorY = ground;
        hopStarted = player.tickCount;
        hopping = true;
        decision = "jump";
        jumps++;
        leftGround = false;
        input.setInputForceState(Input.JUMP, true);
    }

    private boolean hopFloorSupported(BlockPos pos) {
        var floor = ctx.world().getBlockState(pos.below());
        if (!floor.isFaceSturdy(ctx.world(), pos.below(), Direction.UP) || MovementHelper.avoidWalkingInto(floor)
                || floor.is(Blocks.ICE) || floor.is(Blocks.PACKED_ICE) || floor.is(Blocks.BLUE_ICE)
                || floor.is(Blocks.HONEY_BLOCK) || floor.is(Blocks.SLIME_BLOCK)) return false;
        return true;
    }

    private boolean hopBodyClear(BlockPos from, BlockPos to, double sideX, double sideZ,
                                 double lateral, double landingLateral) {
        double margin = ctx.player().getBbWidth() / 2 + .15;
        double minX = Math.min(from.getX(), to.getX()) + .5 + Math.min(sideX * lateral, sideX * landingLateral) - margin;
        double maxX = Math.max(from.getX(), to.getX()) + .5 + Math.max(sideX * lateral, sideX * landingLateral) + margin;
        double minZ = Math.min(from.getZ(), to.getZ()) + .5 + Math.min(sideZ * lateral, sideZ * landingLateral) - margin;
        double maxZ = Math.max(from.getZ(), to.getZ()) + .5 + Math.max(sideZ * lateral, sideZ * landingLateral) + margin;
        for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX); x++)
            for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ); z++)
                for (int y = from.getY(); y <= from.getY() + 3; y++)
                    if (!MovementHelper.fullyPassable(ctx, new BlockPos(x, y, z))) return false;
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
