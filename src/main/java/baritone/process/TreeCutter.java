/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.look.ForkableRandom;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementOption;
import baritone.process.TreeCutModel.Log;
import baritone.process.TreeCutModel.Pattern;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Cuts trees the way a person does. It runs to a tree, starts cutting from a couple of blocks out
 * as it arrives, and closes in. Once the two lowest cells are clear it steps under the trunk and
 * takes the rest looking up through the gap; otherwise it stays pressed against the trunk. The
 * gap is what reaches the logs inside a canopy, so leaves are only cut when nothing else can.
 */
final class TreeCutter {
    /** Where the pathfinder gives way to running straight in with an aimed camera. */
    private static final double CLOSE_RANGE = 7;
    private static final double PRESSED = .8, MOVE_TOLERANCE = .12, COAST = 1 / .454, SPRINT_LIMIT = .18, SPRINT_FROM = 3.2;
    /** The recordings kept attack down through 14 of 25 walks between trees. */
    private static final double KEEP_ATTACK_BETWEEN_TREES = .56;
    /** Some recordings kept shifting about while cutting; the others mostly stood still. */
    private static final double RESTLESS_SHARE = .5;
    /** How far off a log's middle the eyes can be for the log to count as overhead, and how far from a face's edge to aim. */
    private static final double OVERHEAD_RANGE = 1.1, OVERHEAD_MARGIN = .2;
    private static final int STUCK_TICKS = 15, FALLBACK_TICKS = 40, NO_VIEW_TICKS = 25, CLEARING_TICKS = 160, LOG_TICKS = 200;

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private final MineProcess mine;
    private final Predicate<BlockState> wanted;
    private final Consumer<BlockPos> giveUp;
    private final Set<Log> skipped = new HashSet<>();

    private ForkableRandom random;
    private boolean restless;
    private List<Log> order = List.of();
    private int startSize, trees;
    private double firstStandoff, secondStandoff, pressDistance, underX, underZ;
    private Log target;
    private Direction targetFace;
    private boolean closing, attackWanted;
    private int releaseIn, stuckTicks, fallbackTicks, noViewTicks, clearingTicks, targetTicks, fidgetTicks, fidgetWait;
    private Input fidgetKey;
    private boolean fidgetForward;
    private double lastX, lastZ;

    TreeCutter(Baritone baritone, MineProcess mine, Predicate<BlockState> wanted, Consumer<BlockPos> giveUp) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.mine = mine;
        this.wanted = wanted;
        this.giveUp = giveUp;
    }

    void reset() {
        random = null;
        order = List.of();
        skipped.clear();
        target = null;
        targetFace = null;
        closing = attackWanted = false;
        trees = 0;
        releaseIn = stuckTicks = fallbackTicks = noViewTicks = clearingTicks = targetTicks = 0;
    }

    /** Held only over a log, leaves or empty air, so nothing else gets cut. */
    boolean isHoldingAttack() {
        if (!attackWanted || ctx.player() == null) return false;
        HitResult hit = ctx.objectMouseOver();
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return true;
        BlockState state = ctx.world().getBlockState(block.getBlockPos());
        return wanted.test(state) || state.is(BlockTags.LEAVES);
    }

    boolean closing() {
        return closing;
    }

    String phase() {
        return closing ? "harvest" : "approach";
    }

    BlockPos target() {
        return target == null ? null : pos(target);
    }

    int treesStarted() {
        return trees;
    }

    /** The pathfinder found no way to this tree. */
    void abandonTree() {
        order.forEach(this::skip);
        order = List.of();
        target = null;
        closing = false;
    }

    /** @return the command for this tick, or null when no log is left that it can get to */
    PathingCommand tick(List<BlockPos> known, boolean safeToCancel) {
        if (random == null) start();
        List<Log> remaining = order.stream().filter(this::alive).toList();
        if (remaining.isEmpty()) {
            if (!order.isEmpty()) finishTree();
            remaining = pickTree(known);
            if (remaining.isEmpty()) {
                closing = attackWanted = false;
                return null;
            }
        }
        double x = ctx.player().getX(), z = ctx.player().getZ();
        double distance = TreeCutModel.distance(remaining, x, z);
        trackAttack(distance);
        if (fallbackTicks > 0) fallbackTicks--;
        if (distance > CLOSE_RANGE || fallbackTicks > 0 || !ctx.player().onGround() || !safeToCancel) {
            closing = false;
            target = null;
            Log base = remaining.stream().min(Comparator.comparingInt(Log::y)).orElseThrow();
            return new PathingCommand(new GoalNear(new BlockPos(base.x(), base.y(), base.z()), 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        closing = true;
        cut(remaining, x, z);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private void start() {
        random = new ForkableRandom(Baritone.settings().naturalMovementSeed.value ^ 0x2f1d97c3a65be081L);
        restless = random.nextDouble() < RESTLESS_SHARE;
        lastX = ctx.player().getX();
        lastZ = ctx.player().getZ();
        fidgetWait = 10;
    }

    private List<Log> pickTree(List<BlockPos> known) {
        List<Log> logs = new ArrayList<>();
        for (BlockPos pos : known) {
            Log log = new Log(pos.getX(), pos.getY(), pos.getZ());
            if (!skipped.contains(log) && alive(log)) logs.add(log);
        }
        if (logs.isEmpty()) return List.of();
        List<Log> tree = TreeCutModel.nextTree(TreeCutModel.trees(logs), ctx.player().getX(), ctx.player().getZ(), random);
        order = TreeCutModel.cutOrder(tree, TreeCutModel.pattern(random.nextDouble()));
        startSize = order.size();
        trees++;
        // Distances in the recordings at the first and second log of a tree: quartiles.
        firstStandoff = 1.9 + random.nextDouble() * .7;
        secondStandoff = .85 + random.nextDouble() * .4;
        pressDistance = 3.4 + random.nextDouble() * 1.3;
        double angle = random.nextDouble() * Math.PI * 2, offset = .05 + random.nextDouble() * .3;
        underX = Math.cos(angle) * offset;
        underZ = Math.sin(angle) * offset;
        target = null;
        noViewTicks = clearingTicks = targetTicks = 0;
        return order;
    }

    private void finishTree() {
        order = List.of();
        target = null;
        if (attackWanted && random.nextDouble() >= KEEP_ATTACK_BETWEEN_TREES) releaseIn = 5 + (int) (random.nextDouble() * 17);
    }

    private void trackAttack(double distance) {
        if (releaseIn > 0 && --releaseIn == 0) attackWanted = false;
        if (distance <= pressDistance) {
            attackWanted = true;
            releaseIn = 0;
        }
    }

    private void cut(List<Log> remaining, double x, double z) {
        chooseTarget(remaining);
        Log column = target != null ? target : remaining.getFirst();
        move(column, remaining, x, z);
        if (noViewTicks > NO_VIEW_TICKS && target == null) clearInTheWay(remaining);
        trackStuck(column, x, z);
    }

    private void chooseTarget(List<Log> remaining) {
        if (target != null && (!alive(target) || ++targetTicks > LOG_TICKS)) {
            // A log that ignores this long of held attack is not going to break.
            if (alive(target)) {
                skip(target);
                remaining = remaining.stream().filter(log -> !log.equals(target)).toList();
            }
            target = null;
        }
        if (target != null) {
            Optional<Rotation> aim = aimAt(target);
            if (aim.isPresent()) {
                baritone.getLookBehavior().updateTarget(aim.get(), true);
                noViewTicks = 0;
                return;
            }
            target = null;
        }
        for (Log log : remaining) {
            Optional<Rotation> aim = aimAt(log);
            if (aim.isEmpty()) continue;
            target = log;
            targetTicks = 0;
            noViewTicks = clearingTicks = 0;
            baritone.getLookBehavior().updateTarget(aim.get(), true);
            MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, pos(log)));
            return;
        }
        noViewTicks++;
        if (!remaining.isEmpty()) {
            // Nothing visible yet: face the trunk while walking in.
            Vec3 middle = Vec3.atCenterOf(pos(remaining.getFirst()));
            baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), middle, ctx.playerRotations()), false);
        }
    }

    /** A stable point on the face turned most toward the eyes, with a clear line from them. */
    private Optional<Rotation> aimAt(Log log) {
        BlockPos pos = pos(log);
        Vec3 eyes = ctx.playerHead();
        Optional<Rotation> overhead = tiltUpTo(pos, eyes);
        if (overhead.isPresent()) return overhead;
        if (log.equals(target) && targetFace != null) {
            Optional<Rotation> kept = visible(pos, targetFace, eyes);
            if (kept.isPresent()) return kept;
        }
        Vec3 toEyes = eyes.subtract(Vec3.atCenterOf(pos)).normalize();
        List<Direction> faces = new ArrayList<>(List.of(Direction.values()));
        faces.sort(Comparator.comparingDouble(face -> -(toEyes.x * face.getStepX() + toEyes.y * face.getStepY() + toEyes.z * face.getStepZ())));
        // A log below the eyes gets taken from its top once that is open, not by looking down its side.
        if (pos.getY() + 1 <= eyes.y) {
            faces.remove(Direction.UP);
            faces.addFirst(Direction.UP);
        }
        for (Direction face : faces) {
            Optional<Rotation> aim = visible(pos, face, eyes);
            if (aim.isPresent()) {
                if (log.equals(target) || target == null) targetFace = face;
                return aim;
            }
        }
        return Optional.empty();
    }

    /** Under a trunk a person tilts the view up and leaves its bearing alone. */
    private Optional<Rotation> tiltUpTo(BlockPos pos, Vec3 eyes) {
        double height = pos.getY() + .002 - eyes.y;
        double reach = ctx.playerController().getBlockReachDistance();
        if (height <= 0 || Math.hypot(pos.getX() + .5 - eyes.x, pos.getZ() + .5 - eyes.z) > OVERHEAD_RANGE) return Optional.empty();
        float yaw = ctx.playerRotations().getYaw();
        double headingX = -Math.sin(Math.toRadians(yaw)), headingZ = Math.cos(Math.toRadians(yaw));
        // The recordings look up at about 78 degrees rather than straight up; each log keeps its own lean.
        double lean = height * (.12 + new ForkableRandom(Baritone.settings().naturalMovementSeed.value ^ pos.asLong()).nextDouble() * .25);
        double best = -1;
        for (double along = 0; along <= OVERHEAD_RANGE * 2; along += .05) {
            double x = eyes.x + headingX * along, z = eyes.z + headingZ * along;
            if (x < pos.getX() + OVERHEAD_MARGIN || x > pos.getX() + 1 - OVERHEAD_MARGIN
                    || z < pos.getZ() + OVERHEAD_MARGIN || z > pos.getZ() + 1 - OVERHEAD_MARGIN) continue;
            if (best < 0 || Math.abs(along - lean) < Math.abs(best - lean)) best = along;
        }
        if (best < 0) return Optional.empty();
        Vec3 aim = new Vec3(eyes.x + headingX * best, pos.getY() + .002, eyes.z + headingZ * best);
        if (eyes.distanceToSqr(aim) > reach * reach) return Optional.empty();
        BlockHitResult hit = ctx.world().clip(new ClipContext(eyes, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player()));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos) || hit.getDirection() != Direction.DOWN) return Optional.empty();
        return Optional.of(new Rotation(yaw, (float) -Math.toDegrees(Math.atan2(height, best))));
    }

    private Optional<Rotation> visible(BlockPos pos, Direction face, Vec3 eyes) {
        ForkableRandom point = new ForkableRandom(Baritone.settings().naturalMovementSeed.value ^ pos.asLong() ^ face.ordinal() * 0x61c88647L);
        Vec3 aim = facePoint(pos, face, .32 + point.nextDouble() * .36, .32 + point.nextDouble() * .36);
        double reach = ctx.playerController().getBlockReachDistance();
        if (eyes.distanceToSqr(aim) > reach * reach) return Optional.empty();
        BlockHitResult hit = ctx.world().clip(new ClipContext(eyes, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player()));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos) || hit.getDirection() != face) return Optional.empty();
        double horizontal = Math.hypot(aim.x - eyes.x, aim.z - eyes.z);
        // Straight overhead, the bearing to the aim point means nothing and would spin the view.
        if (horizontal < .2) {
            return Optional.of(new Rotation(ctx.playerRotations().getYaw(), (float) -Math.toDegrees(Math.atan2(aim.y - eyes.y, horizontal))));
        }
        return Optional.of(RotationUtils.calcRotationFromVec3d(eyes, aim, ctx.playerRotations()));
    }

    private static Vec3 facePoint(BlockPos pos, Direction face, double u, double v) {
        return switch (face) {
            case UP -> new Vec3(pos.getX() + u, pos.getY() + .998, pos.getZ() + v);
            case DOWN -> new Vec3(pos.getX() + u, pos.getY() + .002, pos.getZ() + v);
            case NORTH -> new Vec3(pos.getX() + u, pos.getY() + v, pos.getZ() + .002);
            case SOUTH -> new Vec3(pos.getX() + u, pos.getY() + v, pos.getZ() + .998);
            case WEST -> new Vec3(pos.getX() + .002, pos.getY() + v, pos.getZ() + u);
            case EAST -> new Vec3(pos.getX() + .998, pos.getY() + v, pos.getZ() + u);
        };
    }

    /** Closes in by stages as logs fall, then stays pressed against the trunk or steps under it. */
    private void move(Log column, List<Log> remaining, double x, double z) {
        int cut = startSize - remaining.size();
        Vec3 motion = ctx.player().getDeltaMovement();
        double coastX = x + motion.x * COAST, coastZ = z + motion.z * COAST;
        double centerX = column.x() + .5, centerZ = column.z() + .5;
        double moveX, moveZ;
        boolean under = cut >= 1 && column.y() >= ctx.playerFeet().getY() + 2 && canStandUnder(column);
        if (under) {
            moveX = centerX + underX - coastX;
            moveZ = centerZ + underZ - coastZ;
        } else {
            double wanted = target == null || cut >= 2 ? PRESSED : cut == 0 ? firstStandoff : secondStandoff;
            double fromCenter = Math.hypot(coastX - centerX, coastZ - centerZ);
            double excess = fromCenter - wanted;
            if (excess <= 0 || fromCenter < 1e-3) {
                moveX = moveZ = 0;
            } else {
                moveX = (centerX - coastX) / fromCenter * excess;
                moveZ = (centerZ - coastZ) / fromCenter * excess;
            }
        }
        if (Math.hypot(moveX, moveZ) >= MOVE_TOLERANCE && press(moveX, moveZ, Math.hypot(x - centerX, z - centerZ))) return;
        if (restless && cut >= 1) fidget();
    }

    /** Whether the body fits in the column once the logs at the feet and head are gone. */
    private boolean canStandUnder(Log column) {
        var box = ctx.player().getBoundingBox().move(column.x() + .5 - ctx.player().getX(), 0, column.z() + .5 - ctx.player().getZ());
        return ctx.world().noCollision(ctx.player(), box);
    }

    private boolean press(double moveX, double moveZ, double fromTree) {
        double length = Math.hypot(moveX, moveZ);
        double dirX = moveX / length, dirZ = moveZ / length;
        double yaw = Math.toRadians(ctx.player().getYRot());
        List<MovementOption> options = MovementOption.getOptions((float) Math.sin(yaw), (float) Math.cos(yaw), false)
                .sorted(Comparator.comparingDouble(option -> -alignment(option, dirX, dirZ))).toList();
        for (MovementOption option : options) {
            if (alignment(option, dirX, dirZ) < .5) break;
            double size = Math.hypot(option.motionX(), option.motionZ());
            if (!floorAhead(-option.motionX() / size, option.motionZ() / size)) continue;
            var input = baritone.getInputOverrideHandler();
            input.setInputForceState(option.input1(), true);
            if (option.input2() != null) input.setInputForceState(option.input2(), true);
            boolean sprint = option.input1() == Input.MOVE_FORWARD && option.input2() == null && fromTree > SPRINT_FROM && ctx.player().getSpeed() < SPRINT_LIMIT;
            input.setInputForceState(Input.SPRINT, sprint);
            return true;
        }
        return false;
    }

    private static double alignment(MovementOption option, double dirX, double dirZ) {
        double size = Math.hypot(option.motionX(), option.motionZ());
        return (-option.motionX() * dirX + option.motionZ() * dirZ) / size;
    }

    /** Pushing into the trunk is fine, walking off a ledge is not. */
    private boolean floorAhead(double dirX, double dirZ) {
        double step = ctx.player().getSpeed() * COAST;
        Vec3 motion = ctx.player().getDeltaMovement();
        return mine.naturalFloorSupported(dirX * step + motion.x * COAST, dirZ * step + motion.z * COAST);
    }

    private void fidget() {
        if (fidgetTicks > 0) {
            fidgetTicks--;
            double yaw = Math.toRadians(ctx.player().getYRot());
            double side = fidgetKey == Input.MOVE_LEFT ? 1 : -1;
            boolean forward = fidgetForward || fidgetKey == Input.MOVE_FORWARD;
            double dirX = (forward ? -Math.sin(yaw) : 0) + (fidgetKey == Input.MOVE_FORWARD ? 0 : side * Math.cos(yaw));
            double dirZ = (forward ? Math.cos(yaw) : 0) + (fidgetKey == Input.MOVE_FORWARD ? 0 : side * Math.sin(yaw));
            double length = Math.hypot(dirX, dirZ);
            if (!floorAhead(dirX / length, dirZ / length)) return;
            baritone.getInputOverrideHandler().setInputForceState(fidgetKey, true);
            if (fidgetForward) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        } else if (--fidgetWait <= 0) {
            double roll = random.nextDouble();
            fidgetKey = roll < .4 ? Input.MOVE_LEFT : roll < .8 ? Input.MOVE_RIGHT : Input.MOVE_FORWARD;
            fidgetForward = fidgetKey != Input.MOVE_FORWARD && random.nextDouble() < .5;
            fidgetTicks = 2 + (int) (random.nextDouble() * 4);
            fidgetWait = 5 + (int) (random.nextDouble() * 12);
        }
    }

    /** No log has a clear line: aim at one in reach and cut the leaves in front of it. Anything else in the way ends this tree. */
    private void clearInTheWay(List<Log> remaining) {
        Vec3 eyes = ctx.playerHead();
        double reach = ctx.playerController().getBlockReachDistance();
        if (clearingTicks++ <= CLEARING_TICKS) {
            for (Log log : remaining) {
                Vec3 middle = Vec3.atCenterOf(pos(log));
                if (eyes.distanceToSqr(middle) > reach * reach) continue;
                BlockHitResult hit = ctx.world().clip(new ClipContext(eyes, middle, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player()));
                if (hit.getType() != HitResult.Type.BLOCK || !ctx.world().getBlockState(hit.getBlockPos()).is(BlockTags.LEAVES)) continue;
                attackWanted = true;
                baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eyes, middle, ctx.playerRotations()), true);
                MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, pos(log)));
                return;
            }
        }
        Helper.HELPER.logDirect("Leaving " + remaining.size() + " log(s) of this tree out of reach");
        abandonTree();
    }

    private void trackStuck(Log column, double x, double z) {
        boolean moved = Math.hypot(x - lastX, z - lastZ) > .02;
        lastX = x;
        lastZ = z;
        boolean tryingToMove = target == null && Math.hypot(x - column.x() - .5, z - column.z() - .5) > 1.1;
        stuckTicks = moved || !tryingToMove ? 0 : stuckTicks + 1;
        if (stuckTicks < STUCK_TICKS) return;
        // Something stands between here and the trunk, so let the pathfinder walk around it.
        stuckTicks = 0;
        fallbackTicks = FALLBACK_TICKS;
    }

    private boolean alive(Log log) {
        return wanted.test(BlockStateInterface.get(ctx, pos(log)));
    }

    private void skip(Log log) {
        skipped.add(log);
        giveUp.accept(pos(log));
    }

    private static BlockPos pos(Log log) {
        return new BlockPos(log.x(), log.y(), log.z());
    }
}
