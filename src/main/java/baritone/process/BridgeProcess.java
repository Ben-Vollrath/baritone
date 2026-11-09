/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.look.ForkableRandom;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bridges straight over a gap the way a person does, as measured in {@link BridgeModel}: sprint to the
 * edge, sneak, turn round to the left, then back across on S+D with right-click held, and look back along
 * the bridge once on the far side. Clicks are vanilla's own repeat of the held use key, so placement
 * timing and reach are the game's. Use is held only while the crosshair is on the bridge's own top face or
 * its side facing the gap, so nothing is placed anywhere else, and sneak stays down until the body is on
 * the landing, so vanilla keeps it from walking off.
 */
public final class BridgeProcess extends BaritoneProcessHelper {
    /** Longest gap it will take on. */
    private static final int MAX_GAP = 48;
    /** Ticks without a new block, while bridging, before it gives up. */
    private static final int STALL_TICKS = 60;
    /** Ticks at the edge without a placement before the aim is corrected. */
    private static final int AIM_STALL = 10;
    /** Turn progress counted from yaw: the turn is done within this many degrees. */
    private static final double TURN_DONE = 8;
    /** How long it stands still at the end before the job is over. */
    private static final int END_TICKS = 10;

    private enum Phase { REACT, APPROACH, TURN, BRIDGE, EXIT_BACK, EXIT_TURN, SETTLE, WALK, RETREAT }
    /** Degrees of yaw per block of sideways error, and the most it turns for it. */
    private static final double LATERAL_GAIN = 25, LATERAL_LIMIT = 8;
    private static final int RETREAT_TICKS = 100, EDGE_HOLD_TICKS = 20;

    private boolean active;
    private Phase phase;
    private ForkableRandom random;
    private BlockPos goal;
    /** Unit direction of travel, the lateral line, and the floor's y. */
    private int dx, dz, floorY;
    /** Along-axis coordinates (d·position) of the gap's start and the landing's start faces. */
    private double edge, landing;
    private int lateral;
    private double heading, bridgeYaw, bridgePitch, pitchNow, yawDrift, pitchDrift, sneakBefore, useFrom;
    private double useReleaseAt, sneakReleaseAt, turnAt, endYaw, endPitch, turnPitch, aimFix, keepLeft, lastError;
    private double[] turnKeyPoints;
    private int wait, sinceBlock, atEdge, turnSdTicks, turnBackTicks, endTicks, blocksPlaced, driftWait;
    private boolean useDown, turnPitchDone;
    private int edgeHoldUntil = Integer.MIN_VALUE;
    private String failure;

    public BridgeProcess(Baritone baritone) {
        super(baritone);
    }

    /** Bridges from where the player stands towards this goal's column, then walks to the goal. */
    public void bridge(BlockPos goal) {
        var feet = ctx.playerFeet();
        int ax = goal.getX() - feet.getX(), az = goal.getZ() - feet.getZ();
        if (Math.abs(ax) >= Math.abs(az)) { dx = Integer.signum(ax); dz = 0; } else { dx = 0; dz = Integer.signum(az); }
        if (dx == 0 && dz == 0) throw new IllegalArgumentException("The goal is where the player stands");
        floorY = feet.getY() - 1;
        lateral = dx != 0 ? feet.getZ() : feet.getX();
        BlockPos gapStart = null, landingStart = null;
        int from = along(feet), to = along(goal);
        for (int a = from; a <= Math.max(to, from + 1) + MAX_GAP; a++) {
            var floor = cell(a);
            boolean solid = solidFloor(floor);
            if (gapStart == null && !solid) gapStart = floor;
            else if (gapStart != null && solid) { landingStart = floor; break; }
        }
        if (gapStart == null) throw new IllegalStateException("No gap ahead towards the goal");
        if (landingStart == null) throw new IllegalStateException("No landing within " + MAX_GAP + " blocks");
        int gap = along(landingStart) - along(gapStart);
        if (gap > MAX_GAP) throw new IllegalStateException("The gap is longer than " + MAX_GAP + " blocks");
        if (blocksHeld() < gap) throw new IllegalStateException("Not enough blocks for a " + gap + "-block gap");
        this.goal = goal;
        edge = along(gapStart);
        landing = along(landingStart);
        random = new ForkableRandom(Baritone.settings().naturalMovementSeed.value ^ 0x2b7e151628aed2a6L);
        heading = BridgeModel.heading(dx, dz);
        bridgeYaw = heading + 180 + BridgeModel.bridgeYawOffset(random);
        bridgePitch = BridgeModel.bridgePitch(random);
        turnPitch = BridgeModel.turnPitch(random);
        pitchNow = BridgeModel.firstBlockPitch(bridgePitch, random);
        sneakBefore = BridgeModel.sneakBefore(random);
        useFrom = BridgeModel.useFrom(random);
        useReleaseAt = BridgeModel.useReleaseAt(random);
        sneakReleaseAt = BridgeModel.sneakReleaseAt(random);
        turnAt = BridgeModel.turnAt(random);
        turnSdTicks = BridgeModel.turnSdTicks(random);
        turnBackTicks = BridgeModel.turnBackTicks(random);
        endYaw = heading + 180 + BridgeModel.endYawOffset(random);
        endPitch = BridgeModel.endPitch(random);
        turnKeyPoints = BridgeModel.turnKeyPoints(random);
        wait = BridgeModel.reactionTicks(random);
        yawDrift = pitchDrift = aimFix = lastError = 0;
        keepLeft = BridgeModel.keepLeft(random);
        driftWait = BridgeModel.driftRest(random);
        sinceBlock = atEdge = endTicks = blocksPlaced = 0;
        turnPitchDone = false;
        failure = null;
        edgeHoldUntil = Integer.MIN_VALUE;
        phase = Phase.REACT;
        active = true;
    }

    /** Whether sneak is held for a moment after a stop at the edge; see {@link #finish}. */
    public boolean holdsEdge() {
        return ctx.player() != null && ctx.player().tickCount < edgeHoldUntil;
    }

    /** Why the last job gave up, or null. */
    public String failure() { return failure; }

    public int blocksPlaced() { return blocksPlaced; }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (ctx.player() == null || !ctx.player().isAlive()) { finish(null); return null; }
        // Below the floor means it went over the side: nothing more to do safely.
        if (ctx.player().getY() < floorY + .5) { finish("Fell off the bridge"); return null; }
        double s = along(ctx.player().position());
        if ((phase == Phase.APPROACH || phase == Phase.TURN || phase == Phase.BRIDGE) && !holdBlocks()) {
            fail("No blocks left on the hotbar");
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        switch (phase) {
            case REACT -> {
                keys(false, false, false, false, false, false);
                look(heading, ctx.player().getXRot());
                if (--wait <= 0) phase = Phase.APPROACH;
            }
            case APPROACH -> {
                // Looking at the edge block's top as it comes closer.
                Vec3 edgeTop = Vec3.atBottomCenterOf(cell((int) Math.floor(edge) - 1)).add(0, 1, 0);
                double pitch = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), edgeTop, ctx.playerRotations()).getPitch();
                look(heading, Math.min(60, pitch));
                boolean sneak = s >= edge - sneakBefore;
                keys(true, false, false, false, sneak, !sneak);
                if (sneak) phase = Phase.TURN;
            }
            case TURN -> {
                double yaw = ctx.player().getYRot();
                double left = BridgeModel.wrap(bridgeYaw - yaw);
                double progress = 1 - Math.abs(left) / 135;
                // The view swings low through the turn and comes up to the bridging pitch at its end.
                if (progress > .8) turnPitchDone = true;
                look(yaw + left, turnPitchDone ? pitchNow : turnPitch);
                var turnKeys = BridgeModel.turnKeys(progress, turnKeyPoints[0], turnKeyPoints[1], turnKeyPoints[2]);
                keys(turnKeys == BridgeModel.TurnKeys.W || turnKeys == BridgeModel.TurnKeys.WD, turnKeys == BridgeModel.TurnKeys.SD,
                        false, turnKeys != BridgeModel.TurnKeys.W, true, false);
                if (Math.abs(left) < TURN_DONE && turnKeys == BridgeModel.TurnKeys.SD) phase = Phase.BRIDGE;
            }
            case BRIDGE -> bridgeTick(s);
            case RETREAT -> {
                // Back to the middle of the last solid cell, with sneak holding the edge meanwhile.
                Vec3 safe = Vec3.atBottomCenterOf(cell(Math.max((int) edge - 1, (int) edge + blocksPlaced - 1)));
                Vec3 p = ctx.player().position();
                boolean there = Math.hypot(safe.x - p.x, safe.z - p.z) < .2;
                if (there) keys(false, false, false, false, true, false);
                else keysToward(safe.x - p.x, safe.z - p.z);
                if (there || --wait <= 0) { finish(failure); return null; }
            }
            case EXIT_BACK -> {
                look(bridgeYaw + yawDrift, pitchNow + pitchDrift);
                boolean sneak = s < landing + sneakReleaseAt;
                // Backing up without sneak only onto floor: a short landing ends the back-up early.
                boolean floor = floorAhead(1.2);
                keys(false, sneak || floor, false, sneak || floor, sneak, false);
                if (!sneak && (s >= landing + turnAt || !floor)) phase = Phase.EXIT_TURN;
            }
            case EXIT_TURN -> {
                double yaw = ctx.player().getYRot();
                look(yaw + BridgeModel.wrap(endYaw - yaw), endPitch);
                boolean floor = floorAhead(1.2);
                boolean strafe = turnSdTicks-- > 0 && floor;
                boolean back = strafe || turnBackTicks-- > 0 && floor;
                keys(false, back, false, strafe, false, false);
                if (!back && Math.abs(BridgeModel.wrap(endYaw - yaw)) < 3) phase = Phase.SETTLE;
            }
            case SETTLE -> {
                keys(false, false, false, false, false, false);
                Vec3 motion = ctx.player().getDeltaMovement();
                if (Math.hypot(motion.x, motion.z) < .003 && ++endTicks >= END_TICKS) {
                    if (Math.abs(along(goal) - along(ctx.playerFeet())) > 3 || Math.abs(lateralOf(goal) - lateral) > 1) phase = Phase.WALK;
                    else { finish(null); return null; }
                }
            }
            case WALK -> {
                if (ctx.playerFeet().equals(goal)) { finish(null); return null; }
                return new PathingCommand(new GoalBlock(goal), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private void bridgeTick(double s) {
        int next = nextGapCell();
        if (next < 0) {
            // Every gap cell is filled: walk onto the landing still sneaking, and let go of right-click at its edge.
            setUse(s < landing + useReleaseAt && useAllowed(Integer.MIN_VALUE));
            keys(false, true, false, true, true, false);
            look(bridgeYaw + yawDrift, pitchNow + pitchDrift);
            if (s >= landing + useReleaseAt) { setUse(false); phase = Phase.EXIT_BACK; }
            return;
        }
        if (++sinceBlock > STALL_TICKS) { fail("No block placed for " + STALL_TICKS + " ticks"); return; }
        double overhang = s - next;
        // Stuck at the edge with nothing placed: the crosshair is off the side face, so tip the view down a little.
        if (overhang > .25 && motionAlong() < .01) {
            if (++atEdge % AIM_STALL == 0) aimFix += 1.2;
        } else atEdge = 0;
        // Looking back and to the left puts the crosshair left of the body, so the body keeps to the line's right side.
        // With S+D the resting yaw sets the sideways drift: exactly 45 degrees off back goes straight, more drifts right
        // and less drifts left. A person re-sets it now and then rather than steering every tick.
        double error = leftOfLine() - keepLeft;
        if (--driftWait <= 0 || Math.abs(error) > .15 && Math.abs(error - lastError) > .05) {
            driftWait = BridgeModel.driftRest(random);
            yawDrift = Math.max(-LATERAL_LIMIT, Math.min(LATERAL_LIMIT, LATERAL_GAIN * error)) + BridgeModel.driftYaw(random) * .5;
            pitchDrift = BridgeModel.driftPitch(random);
            lastError = error;
        }
        // Ease from the first block's pitch to the bridging pitch over two blocks.
        pitchNow += (bridgePitch - pitchNow) * .06;
        look(bridgeYaw + yawDrift, pitchNow + pitchDrift + aimFix);
        keys(false, true, false, true, true, false);
        setUse((blocksPlaced > 0 || overhang >= useFrom) && useAllowed(next));
    }

    /** The first unfilled gap cell's along-axis coordinate, or -1 if the bridge is complete. Counts blocks as they appear. */
    private int nextGapCell() {
        int filled = 0, next = -1;
        for (int a = (int) edge; a < (int) landing; a++) {
            if (!solidFloor(cell(a))) { next = a; break; }
            filled++;
        }
        if (filled > blocksPlaced) {
            blocksPlaced = filled;
            sinceBlock = 0;
            // An aim correction has done its job and eases off again.
            aimFix *= .5;
        }
        return next;
    }

    /**
     * Right-click may be down only where a click can place nothing but the next bridge block: the gap-facing side
     * of the cell before {@code next}, or the top of a floor cell whose space above the body fills, where vanilla
     * refuses the block. Anything else lets go of it.
     */
    private boolean useAllowed(int next) {
        HitResult hit = ctx.objectMouseOver();
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return false;
        BlockPos pos = block.getBlockPos();
        if (pos.getY() != floorY || lateralOf(pos) != lateral) return false;
        Direction ahead = Direction.getApproximateNearest(dx, 0, dz);
        if (block.getDirection() == ahead) return along(pos) == next - 1;
        return block.getDirection() == Direction.UP
                && ctx.player().getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos.above()));
    }

    private void setUse(boolean down) {
        if (down == useDown) return;
        ctx.minecraft().options.keyUse.setDown(down);
        useDown = down;
    }

    private void look(double yaw, double pitch) {
        baritone.getLookBehavior().updateTarget(new Rotation((float) yaw, (float) Math.max(-90, Math.min(90, pitch))), false);
    }

    private void keys(boolean forward, boolean back, boolean left, boolean right, boolean sneak, boolean sprint) {
        var input = baritone.getInputOverrideHandler();
        input.setInputForceState(Input.MOVE_FORWARD, forward);
        input.setInputForceState(Input.MOVE_BACK, back);
        input.setInputForceState(Input.MOVE_LEFT, left);
        input.setInputForceState(Input.MOVE_RIGHT, right);
        input.setInputForceState(Input.SNEAK, sneak);
        input.setInputForceState(Input.SPRINT, sprint);
    }

    /** A placeable block in hand, from the hotbar; false if there is none. */
    private boolean holdBlocks() {
        var inventory = ctx.player().getInventory();
        if (placeable(inventory.getSelectedItem())) return true;
        for (int slot = 0; slot < 9; slot++) {
            if (placeable(inventory.getItem(slot))) { inventory.setSelectedSlot(slot); return true; }
        }
        return false;
    }

    private int blocksHeld() {
        int count = 0;
        var inventory = ctx.player().getInventory();
        for (int slot = 0; slot < 9; slot++) if (placeable(inventory.getItem(slot))) count += inventory.getItem(slot).getCount();
        return count;
    }

    private static boolean placeable(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem item
                && Baritone.settings().acceptableThrowawayItems.value.contains(item);
    }

    private boolean solidFloor(BlockPos pos) {
        return ctx.world().getBlockState(pos).isFaceSturdy(ctx.world(), pos, Direction.UP);
    }

    private double motionAlong() {
        Vec3 motion = ctx.player().getDeltaMovement();
        return motion.x * dx + motion.z * dz;
    }

    private int along(BlockPos pos) { return pos.getX() * dx + pos.getZ() * dz; }

    /** The cell boundary at along-axis coordinate a is the face where cell a starts. */
    private double along(Vec3 pos) {
        double a = pos.x * dx + pos.z * dz;
        // For a negative direction, cell a spans (a-1, a] in this coordinate; shift so cell a starts at a.
        return dx + dz < 0 ? a + 1 : a;
    }

    private int lateralOf(BlockPos pos) { return dx != 0 ? pos.getZ() : pos.getX(); }

    /** How far the body is left of the line's middle, facing the way of travel. */
    private double leftOfLine() {
        // Left of the heading is (cos h, sin h): -z going east, +x going south.
        double h = Math.toRadians(heading);
        Vec3 p = ctx.player().position();
        return dx != 0 ? (p.z - (lateral + .5)) * Math.sin(h) : (p.x - (lateral + .5)) * Math.cos(h);
    }

    /** Gives up, but first steps back onto solid floor still sneaking, so letting go of sneak can't drop it. */
    private void fail(String reason) {
        failure = reason;
        setUse(false);
        phase = Phase.RETREAT;
        wait = RETREAT_TICKS;
    }

    /** Solid floor under the whole body once it has moved this far on, ignoring where it stands now. */
    private boolean floorAhead(double distance) {
        var box = ctx.player().getBoundingBox().move(dx * distance, 0, dz * distance);
        for (int x = (int) Math.floor(box.minX); x <= Math.floor(box.maxX); x++)
            for (int z = (int) Math.floor(box.minZ); z <= Math.floor(box.maxZ); z++)
                if (!solidFloor(new BlockPos(x, floorY, z))) return false;
        return true;
    }

    /** Sneaking keys that walk the body along (x, z), whatever way the view faces. */
    private void keysToward(double x, double z) {
        double yaw = Math.toRadians(ctx.player().getYRot());
        double forward = -Math.sin(yaw) * x + Math.cos(yaw) * z, left = Math.cos(yaw) * x + Math.sin(yaw) * z;
        double size = Math.max(1e-6, Math.hypot(forward, left));
        keys(forward / size > .38, forward / size < -.38, left / size > .38, left / size < -.38, true, false);
    }

    private BlockPos cell(int a) {
        return dx != 0 ? new BlockPos(a * dx, floorY, lateral) : new BlockPos(lateral, floorY, a * dz);
    }

    private void finish(String failure) {
        if (active && failure != null) this.failure = failure;
        // Stopped while over the edge and moving: without sneak the body would coast off it. Holding sneak until the
        // motion has died leaves it standing, still overhanging but supported.
        if (active && ctx.player() != null && (phase == Phase.TURN || phase == Phase.BRIDGE || phase == Phase.EXIT_BACK || phase == Phase.RETREAT))
            edgeHoldUntil = ctx.player().tickCount + EDGE_HOLD_TICKS;
        active = false;
        setUse(false);
        keys(false, false, false, false, false, false);
    }

    @Override
    public void onLostControl() {
        finish(null);
    }

    @Override
    public String displayName0() {
        return "Bridging" + (goal == null ? "" : " to " + goal.toShortString());
    }
}
