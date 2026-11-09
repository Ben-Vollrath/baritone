/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.*;
import baritone.api.process.IMineProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.cache.CachedChunk;
import baritone.cache.FasterWorldScanner;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementOption;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.RouteVariation;
import baritone.behavior.look.ForkableRandom;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.stream.Collectors;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Mine blocks of a certain type
 *
 * @author leijurv
 */
public final class MineProcess extends BaritoneProcessHelper implements IMineProcess {

    private BlockOptionalMetaLookup filter;
    private List<BlockPos> knownOreLocations;
    private List<BlockPos> blacklist; // inaccessible
    private Map<BlockPos, Long> anticipatedDrops;
    private BlockPos branchPoint;
    private GoalRunAway branchPointRunaway;
    private int desiredQuantity;
    private int tickCount;
    private List<BlockPos> naturalBatch;
    private BlockPos naturalStance, naturalTarget;
    private List<BlockPos> naturalDropArea = Collections.emptyList();
    private boolean naturalCollecting;
    private boolean naturalAttackHeld;
    private Float naturalViewPitch;
    private long naturalCollectionUntil;
    private int naturalRetryAfter;
    private BlockPos naturalAimBlock;
    private Direction naturalAimFace;
    private BlockPos naturalAdvanceOre, naturalAdvanceNext;
    private BlockPos naturalApproachOre;
    private Vec3 naturalAdvanceOrigin, naturalAdvancePoint;
    private int naturalAdvanceAfter;
    private int naturalBatchId;
    private boolean naturalApproaching, naturalWorkingEstablished;
    private BlockPos naturalAcquireOre;
    private int naturalAcquireTick=-1, naturalAcquireTicks;
    private boolean treeJob;
    private final TreeCutter trees;

    public MineProcess(Baritone baritone) {
        super(baritone);
        this.trees = new TreeCutter(baritone, this, state -> filter != null && filter.has(state), pos -> {
            blacklist.add(pos);
            knownOreLocations.remove(pos);
        });
    }

    @Override
    public boolean isActive() {
        return filter != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        naturalApproaching=false;
        if (desiredQuantity > 0) {
            int curr = ctx.player().getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> filter.has(stack))
                    .mapToInt(ItemStack::getCount).sum();
            if (curr >= desiredQuantity) {
                logDirect("Have " + curr + " valid items");
                cancel();
                return null;
            }
        }
        if (calcFailed && treeJob && Baritone.settings().naturalMovement.value) {
            trees.abandonTree();
            calcFailed = false;
        }
        if (calcFailed && naturalStance != null) {
            // A good view does not imply a reachable standing position. Let the
            // original miner make progress before trying another working stance.
            naturalBatch = null;
            naturalStance = naturalTarget = null;
            naturalAttackHeld = false;
            naturalRetryAfter = ctx.player().tickCount + 100;
            calcFailed = false;
        }
        if (calcFailed) {
            if (!knownOreLocations.isEmpty() && Baritone.settings().blacklistClosestOnFailure.value) {
                logDirect("Unable to find any path to " + filter + ", blacklisting presumably unreachable closest instance...");
                if (Baritone.settings().notificationOnMineFail.value) {
                    logNotification("Unable to find any path to " + filter + ", blacklisting presumably unreachable closest instance...", true);
                }
                knownOreLocations.stream().min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).ifPresent(blacklist::add);
                knownOreLocations.removeIf(blacklist::contains);
            } else {
                logDirect("Unable to find any path to " + filter + ", canceling mine");
                if (Baritone.settings().notificationOnMineFail.value) {
                    logNotification("Unable to find any path to " + filter + ", canceling mine", true);
                }
                cancel();
                return null;
            }
        }

        updateLoucaSystem();
        int mineGoalUpdateInterval = Baritone.settings().mineGoalUpdateInterval.value;
        List<BlockPos> curr = new ArrayList<>(knownOreLocations);
        if (mineGoalUpdateInterval != 0 && tickCount++ % mineGoalUpdateInterval == 0) { // big brain
            CalculationContext context = new CalculationContext(baritone, true);
            Baritone.getExecutor().execute(() -> rescan(curr, context));
        }
        if (Baritone.settings().legitMine.value) {
            if (!addNearby()) {
                cancel();
                return null;
            }
        }
        Optional<BlockPos> shaft = curr.stream()
                .filter(pos -> pos.getX() == ctx.playerFeet().getX() && pos.getZ() == ctx.playerFeet().getZ())
                .filter(pos -> pos.getY() >= ctx.playerFeet().getY())
                .filter(pos -> !(BlockStateInterface.get(ctx, pos).getBlock() instanceof AirBlock)) // after breaking a block, it takes mineGoalUpdateInterval ticks for it to actually update this list =(
                .min(Comparator.comparingDouble(ctx.playerFeet().above()::distSqr));
        baritone.getInputOverrideHandler().clearAllKeys();
        if (treeJob && Baritone.settings().naturalMovement.value) {
            PathingCommand cutting = trees.tick(curr, isSafeToCancel);
            if (cutting != null) return cutting;
            // No log it can get to. Only dropped items are left to walk to, if any.
            PathingCommand collecting = updateGoal();
            if (collecting == null) cancel();
            return collecting;
        }
        if (Baritone.settings().naturalMovement.value) {
            PathingCommand batch = naturalBatchTick(curr, isSafeToCancel);
            if (batch != null) return batch;
        }
        if (shaft.isPresent() && ctx.player().onGround()) {
            BlockPos pos = shaft.get();
            BlockState state = baritone.bsi.get0(pos);
            if (!MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
                Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
                if (rot.isPresent() && isSafeToCancel) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                    if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }
        PathingCommand command = updateGoal();
        if (command == null) {
            // none in range
            // maybe say something in chat? (ahem impact)
            cancel();
            return null;
        }
        return command;
    }

    public String naturalPhase() {
        if (isActive() && treeJob && Baritone.settings().naturalMovement.value) return trees.phase();
        return !isActive() ? "idle" : naturalCollecting ? "collect" : naturalApproaching ? "approach"
                : naturalTarget != null ? "harvest" : naturalStance != null ? "approach" : "search";
    }

    public Optional<Float> naturalViewPitch() { return Optional.ofNullable(naturalViewPitch); }

    public BlockPos naturalMiningTarget() { return treeJob ? trees.target() : naturalTarget!=null ? naturalTarget : naturalApproachOre; }
    public BlockPos naturalNextOre() { return naturalAdvanceNext; }
    public int naturalMiningBatchId() { return treeJob ? trees.treesStarted() : naturalBatch==null ? -1 : naturalBatchId; }

    /** Sustained attack from the first acquired ore until the cluster finishes. */
    public boolean isHoldingAttack() {
        if (treeJob) return isActive() && Baritone.settings().naturalMovement.value && trees.isHoldingAttack();
        return isActive() && Baritone.settings().naturalMovement.value && naturalAttackHeld && naturalTarget != null && !naturalCollecting;
    }

    /** Slow the close approach as well as the precision work itself. */
    public boolean shouldSneakForPrecision() {
        if (!isActive() || !Baritone.settings().naturalMovement.value || ctx.player()==null || ctx.player().getSpeed()<=.18) return false;
        if (treeJob) return trees.closing();
        if (naturalAttackHeld || naturalTarget!=null) return true;
        double range=ctx.playerController().getBlockReachDistance()+1;
        return knownOreLocations!=null && knownOreLocations.stream().anyMatch(pos->filter.has(BlockStateInterface.get(ctx,pos))
                && ctx.playerHead().distanceToSqr(VecUtils.getBlockPosCenter(pos))<range*range);
    }

    private PathingCommand naturalBatchTick(List<BlockPos> known, boolean safeToCancel) {
        if (!Baritone.settings().mineScanDroppedItems.value) naturalCollecting = false;
        if (naturalCollecting) {
            List<BlockPos> drops = droppedItemsScan().stream()
                    .filter(pos -> naturalDropArea.stream().anyMatch(ore -> ore.distSqr(pos) <= 16)).distinct().toList();
            if (!drops.isEmpty() || System.currentTimeMillis() < naturalCollectionUntil) {
                List<BlockPos> targets = drops.isEmpty() ? naturalDropArea : drops;
                return new PathingCommand(new GoalComposite(targets.stream().map(GoalTwoBlocks::new).toArray(Goal[]::new)),
                        PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
            naturalCollecting = false;
            naturalViewPitch = null;
        }
        if (ctx.player().tickCount < naturalRetryAfter) return null;
        if (naturalBatch == null) {
            CalculationContext context = new CalculationContext(baritone);
            List<BlockPos> ores = known.stream().distinct().filter(pos -> filter.has(BlockStateInterface.get(ctx, pos)))
                    .filter(pos -> !blacklist.contains(pos) && plausibleToBreak(context, pos)).sorted(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).toList();
            if (ores.isEmpty()) return null;
            BlockPos anchor = ores.get(0);
            var batch = new ArrayList<BlockPos>();
            batch.add(anchor);
            int inventory = ctx.player().getInventory().getNonEquipmentItems().stream().filter(stack -> filter.has(stack)).mapToInt(ItemStack::getCount).sum();
            int maximum = desiredQuantity > 0 ? Math.max(1, Math.min(8, desiredQuantity-inventory)) : 8;
            boolean added;
            do {
                added = false;
                for (BlockPos ore : ores) {
                    if (batch.size() >= maximum) break;
                    if (batch.contains(ore) || anchor.distSqr(ore) > 9) continue;
                    if (batch.stream().anyMatch(other -> other.distSqr(ore) <= 3)) { batch.add(ore); added = true; }
                }
            } while (added && batch.size() < maximum);
            BlockPos stance = naturalWorkingStance(batch);
            // A connected vein need not fit within one safe working radius.
            // Keep a reachable portion instead of abandoning the working-area
            // policy and letting ordinary path mining break the closest ore.
            while (stance==null && batch.size()>1) {
                BlockPos fringe=batch.stream().filter(pos->!pos.equals(anchor))
                        .max(Comparator.comparingDouble(anchor::distSqr)).orElseThrow();
                batch.remove(fringe);
                stance=naturalWorkingStance(batch);
            }
            if (stance == null) return null;
            naturalBatch = batch;
            naturalBatchId++;
            naturalStance = stance;
            naturalApproachOre = null;
            naturalWorkingEstablished = false;
            naturalAdvanceOre = null;
            naturalAdvancePoint = null;
        }
        List<BlockPos> live = naturalBatch.stream().filter(pos -> filter.has(BlockStateInterface.get(ctx, pos))).toList();
        if (live.isEmpty()) {
            naturalAttackHeld = false;
            naturalDropArea = naturalBatch;
            naturalBatch = null;
            naturalStance = naturalTarget = null;
            naturalApproachOre = null;
            // Servers that deliver resources directly to inventory have no
            // collection route. Honor the same policy as the original miner.
            naturalCollecting = Baritone.settings().mineScanDroppedItems.value;
            if (naturalCollecting) naturalCollectionUntil = System.currentTimeMillis() + 1000;
            // Direct delivery can select the next working area immediately.
            // Falling through for one tick would start a path into a solid ore.
            return naturalBatchTick(known, safeToCancel);
        }
        if (naturalTarget != null && !live.contains(naturalTarget)) {
            naturalTarget=naturalAdvanceNext!=null && live.contains(naturalAdvanceNext) ? naturalAdvanceNext : null;
            naturalApproachOre=naturalTarget;
        }
        double reach = ctx.playerController().getBlockReachDistance();
        boolean inRange = live.stream().allMatch(pos -> ctx.playerHead().distanceToSqr(VecUtils.getBlockPosCenter(pos)) < (reach-.1)*(reach-.1));
        boolean inWorkingArea = ctx.playerFeet().equals(naturalStance)
                || naturalAttackHeld && naturalWorkingEstablished && ctx.playerFeet().getY()==naturalStance.getY()
                && Math.hypot(ctx.player().getX()-naturalStance.getX()-.5,ctx.player().getZ()-naturalStance.getZ()-.5)<naturalWorkingRadius();
        if (!inWorkingArea || !inRange || !ctx.player().onGround() || !safeToCancel) {
            naturalAdvancePoint = null;
            if (ctx.player().onGround() && safeToCancel && ctx.playerFeet().getY()==naturalStance.getY()
                    && Math.hypot(ctx.player().getX()-naturalStance.getX()-.5,ctx.player().getZ()-naturalStance.getZ()-.5)<reach+1
                    && naturalMiningApproach(live)) return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
            naturalAttackHeld = false;
            naturalTarget = null;
            naturalWorkingEstablished = false;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT,false);
            return new PathingCommand(new GoalBlock(naturalStance), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        naturalWorkingEstablished=true;
        if (naturalTarget==null && live.contains(naturalApproachOre)) naturalTarget=naturalApproachOre;
        List<BlockPos> targets = new ArrayList<>(live);
        targets.sort(Comparator.comparingDouble(pos -> ctx.playerHead().distanceToSqr(VecUtils.getBlockPosCenter(pos))));
        if (naturalTarget != null) { targets.remove(naturalTarget); targets.add(0, naturalTarget); }
        for (BlockPos pos : targets) {
            var state = BlockStateInterface.get(ctx, pos);
            if (MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) continue;
            var rotation = naturalReachable(pos);
            if (rotation.isEmpty()) continue;
            naturalTarget = pos;
            naturalViewPitch = rotation.get().getPitch();
            baritone.getLookBehavior().updateTarget(rotation.get(), true);
            MovementHelper.switchToBestToolFor(ctx, state);
            if (naturalAcquire(pos)) naturalAttackHeld = true;
            if (naturalAttackHeld)
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            if (naturalAttackHeld) naturalMiningAdvance(live);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        // The visible surface changed or the stance was obstructed. Fall back to
        // normal mining rather than waiting indefinitely with no reachable face.
        naturalBatch = null;
        naturalStance = naturalTarget = null;
        naturalAttackHeld = false;
        naturalRetryAfter = ctx.player().tickCount + 40;
        return null;
    }

    private BlockPos naturalWorkingStance(List<BlockPos> batch) {
        BlockPos anchor = batch.get(0);
        double reach = ctx.playerController().getBlockReachDistance() - .25;
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int floorY = ctx.playerFeet().y;
        var eyeOffset = ctx.playerHead().subtract(ctx.player().position());
        for (int x = anchor.getX()-4; x <= anchor.getX()+4; x++) for (int z = anchor.getZ()-4; z <= anchor.getZ()+4; z++) {
            BlockPos pos = new BlockPos(x, floorY, z);
            var support = BlockStateInterface.get(ctx, pos.below());
            if (!support.isFaceSturdy(ctx.world(), pos.below(), net.minecraft.core.Direction.UP) || MovementHelper.avoidWalkingInto(support)
                    || !MovementHelper.fullyPassable(ctx, pos) || !MovementHelper.fullyPassable(ctx, pos.above())) continue;
            var feet = new net.minecraft.world.phys.Vec3(x+.5, floorY, z+.5);
            var eye = feet.add(eyeOffset);
            if (batch.stream().anyMatch(ore -> eye.distanceToSqr(VecUtils.getBlockPosCenter(ore)) > reach*reach
                    || Math.hypot(ore.getX()+.5-feet.x, ore.getZ()+.5-feet.z) < 2.1)) continue;
            int visible = 0;
            for (BlockPos ore : batch) {
                var hit = ctx.world().clip(new net.minecraft.world.level.ClipContext(eye, VecUtils.getBlockPosCenter(ore),
                        net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, ctx.player()));
                if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(ore)) visible++;
            }
            if (visible == 0) continue;
            double score = feet.distanceTo(ctx.player().position()) + (batch.size()-visible)*1.1
                    + RouteVariation.value(Baritone.settings().naturalMovementSeed.value ^ 0x7381efL, x, z)*.8;
            if (score < bestScore) { bestScore = score; best = pos; }
        }
        return best;
    }

    /** Geometric reachability, independent of how far the hand can turn this tick. */
    private Optional<Rotation> naturalReachable(BlockPos pos) {
        var facing=ctx.playerHead().subtract(VecUtils.getBlockPosCenter(pos)).normalize();
        double cachedArea=naturalAimFace==null ? -1 : facing.x*naturalAimFace.getStepX()
                +facing.y*naturalAimFace.getStepY()+facing.z*naturalAimFace.getStepZ();
        double broadestArea=Math.max(Math.abs(facing.x),Math.max(Math.abs(facing.y),Math.abs(facing.z)));
        if (pos.equals(naturalAimBlock) && naturalAimFace!=null && (naturalAttackHeld || cachedArea>=broadestArea-.25)) {
            var rotation=naturalVisiblePoint(pos,naturalFacePoint(pos,naturalAimFace),naturalAimFace);
            if (rotation.isPresent()) return rotation;
        }
        Rotation best=null;
        Direction bestFace=null;
        double bestScore=Double.POSITIVE_INFINITY;
        var candidates=new EnumMap<Direction,Rotation>(Direction.class);
        double broadest=-1;
        for (Direction face:Direction.values()) {
            var rotation=naturalVisiblePoint(pos,naturalFacePoint(pos,face),face);
            if (rotation.isPresent()) {
                candidates.put(face,rotation.get());
                broadest=Math.max(broadest,facing.x*face.getStepX()+facing.y*face.getStepY()+facing.z*face.getStepZ());
            }
        }
        var random=new ForkableRandom(Baritone.settings().naturalMovementSeed.value ^ pos.asLong() ^ 0x7513abL);
        for (Direction face:Direction.values()) {
            var r=candidates.get(face);
            double area=facing.x*face.getStepX()+facing.y*face.getStepY()+facing.z*face.getStepZ();
            if (r==null || area<broadest-.15) continue;
            double score=Math.hypot(Rotation.normalizeYaw(r.getYaw()-ctx.playerRotations().getYaw()),
                    r.getPitch()-ctx.playerRotations().getPitch())+random.nextDouble();
            if (score<bestScore) { bestScore=score; best=r; bestFace=face; }
        }
        if (best!=null) {
            naturalAimBlock=pos; naturalAimFace=bestFace;
            return Optional.of(best);
        }
        return naturalVisiblePoint(pos,VecUtils.calculateBlockCenter(ctx.world(),pos),null);
    }

    private net.minecraft.world.phys.Vec3 naturalFacePoint(BlockPos pos,Direction face) {
        var random=new ForkableRandom(Baritone.settings().naturalMovementSeed.value ^ pos.asLong() ^ face.ordinal()*0x7193abL);
        // Aim at a stable interior point, not at a grazing edge or a new random
        // point while damage is accumulating. Body motion supplies the tracking
        // corrections; the target itself need not wander.
        double u=.46+random.nextDouble()*.08,v=.46+random.nextDouble()*.08;
        return switch (face) {
            case UP -> new net.minecraft.world.phys.Vec3(pos.getX()+u,pos.getY()+.998,pos.getZ()+v);
            case DOWN -> new net.minecraft.world.phys.Vec3(pos.getX()+u,pos.getY()+.002,pos.getZ()+v);
            case NORTH -> new net.minecraft.world.phys.Vec3(pos.getX()+u,pos.getY()+v,pos.getZ()+.002);
            case SOUTH -> new net.minecraft.world.phys.Vec3(pos.getX()+u,pos.getY()+v,pos.getZ()+.998);
            case WEST -> new net.minecraft.world.phys.Vec3(pos.getX()+.002,pos.getY()+v,pos.getZ()+u);
            case EAST -> new net.minecraft.world.phys.Vec3(pos.getX()+.998,pos.getY()+v,pos.getZ()+u);
        };
    }

    private Optional<Rotation> naturalVisiblePoint(BlockPos pos,net.minecraft.world.phys.Vec3 point,Direction face) {
        double reach = ctx.playerController().getBlockReachDistance();
        if (ctx.playerHead().distanceToSqr(point)<=reach*reach) {
            var hit = ctx.world().clip(new net.minecraft.world.level.ClipContext(ctx.playerHead(), point,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, ctx.player()));
            if (hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                    && (face==null || hit.getDirection()==face))
                return Optional.of(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), point, ctx.playerRotations()));
        }
        return Optional.empty();
    }

    private void naturalMiningAdvance(List<BlockPos> ores) {
        ctx.player().setSprinting(false);
        BlockPos next=naturalNextMiningOre(ores,naturalTarget);
        if (next==null) { naturalAdvanceNext=null; naturalAdvancePoint=null; return; }
        if (naturalAdvancePoint==null || !naturalTarget.equals(naturalAdvanceOre) || !next.equals(naturalAdvanceNext)) {
            naturalAdvanceOre=naturalTarget; naturalAdvanceNext=next;
            naturalAdvanceOrigin=ctx.player().position();
            Vec3 direction=new Vec3(next.getX()-naturalTarget.getX(),0,next.getZ()-naturalTarget.getZ());
            double length=direction.length();
            if (length<.01) { naturalAdvancePoint=null; return; }
            var random=new ForkableRandom(Baritone.settings().naturalMovementSeed.value ^ naturalTarget.asLong() ^ 0x83175bL);
            double distance=Math.min(.6,length*(.35+random.nextDouble()*.2));
            naturalAdvancePoint=naturalAdvanceOrigin.add(direction.scale(distance/length));
            naturalAdvanceAfter=ctx.player().tickCount+2+(int)(random.nextDouble()*3);
        }
        if (naturalAdvancePoint==null || ctx.player().tickCount<naturalAdvanceAfter || !ctx.isLookingAt(naturalTarget)) return;
        Vec3 coast=ctx.player().position().add(ctx.player().getDeltaMovement().scale(1/.454));
        Vec3 toward=naturalAdvancePoint.subtract(naturalAdvanceOrigin).normalize();
        Vec3 remaining=naturalAdvancePoint.subtract(coast);
        if (remaining.dot(toward)<naturalMiningSpeed()/.454*.45) return;
        Vec3 desired=remaining.normalize();
        double yaw=Math.toRadians(ctx.player().getYRot());
        var options=MovementOption.getOptions((float)Math.sin(yaw),(float)Math.cos(yaw),false)
                .sorted(Comparator.comparingDouble((MovementOption option)-> {
                    double size=Math.hypot(option.motionX(),option.motionZ());
                    return -(-option.motionX()*desired.x+option.motionZ()*desired.z)/size;
                })).toList();
        for (var option:options) {
            double size=Math.hypot(option.motionX(),option.motionZ());
            double dx=-option.motionX()/size,dz=option.motionZ()/size;
            if (dx*toward.x+dz*toward.z<=.1 || !naturalStepSafe(dx,dz,ores)) continue;
            baritone.getInputOverrideHandler().setInputForceState(option.input1(),true);
            if (option.input2()!=null) baritone.getInputOverrideHandler().setInputForceState(option.input2(),true);
            return;
        }
    }

    private BlockPos naturalNextMiningOre(List<BlockPos> ores,BlockPos current) {
        return ores.stream().filter(pos->!pos.equals(current)).filter(this::naturalHasVisibleFace)
                .min(Comparator.comparingDouble(current::distSqr)).orElse(null);
    }

    /** A stable hit on the ore is enough; tracking a moving body need not settle. */
    private boolean naturalAcquire(BlockPos ore) {
        int tick=ctx.player().tickCount;
        boolean looking=ctx.isLookingAt(ore);
        if (tick!=naturalAcquireTick) {
            if (!looking) naturalAcquireTicks=0;
            else if (ore.equals(naturalAcquireOre) && tick==naturalAcquireTick+1) naturalAcquireTicks++;
            else naturalAcquireTicks=1;
            naturalAcquireTick=tick;
            naturalAcquireOre=looking ? ore : null;
        }
        return looking && ore.equals(naturalAcquireOre) && naturalAcquireTicks>=2;
    }

    private boolean naturalHasVisibleFace(BlockPos pos) {
        for (var face:Direction.values())
            if (naturalVisiblePoint(pos,naturalFacePoint(pos,face),face).isPresent()) return true;
        return false;
    }

    /** Face the vein while the keys, including A/D, finish the close approach. */
    private boolean naturalMiningApproach(List<BlockPos> ores) {
        var candidates=new ArrayList<>(ores);
        candidates.sort(Comparator.comparingDouble(pos->ctx.playerHead().distanceToSqr(VecUtils.getBlockPosCenter(pos))));
        if (naturalApproachOre!=null && candidates.remove(naturalApproachOre)) candidates.add(0,naturalApproachOre);
        if (naturalTarget!=null && candidates.remove(naturalTarget)) candidates.add(0,naturalTarget);
        for (var ore:candidates) {
            var rotation=naturalReachable(ore);
            if (rotation.isEmpty()) continue;
            naturalApproachOre=ore;
            naturalTarget=ore;
            naturalApproaching=true;
            naturalViewPitch=rotation.get().getPitch();
            baritone.getLookBehavior().updateTarget(rotation.get(),true);
            MovementHelper.switchToBestToolFor(ctx,BlockStateInterface.get(ctx,ore));
            if (naturalAcquire(ore)) naturalAttackHeld=true;
            if (naturalAttackHeld) baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT,true);
            naturalAdvanceNext=naturalNextMiningOre(ores,ore);
            ctx.player().setSprinting(false);
            Vec3 coast=ctx.player().position().add(ctx.player().getDeltaMovement().scale(1/.454));
            Vec3 remaining=new Vec3(naturalStance.getX()+.5-coast.x,0,naturalStance.getZ()+.5-coast.z);
            if (remaining.horizontalDistance()<.15) return true;
            double yaw=Math.toRadians(ctx.player().getYRot());
            var options=MovementOption.getOptions((float)Math.sin(yaw),(float)Math.cos(yaw),false)
                    .sorted(Comparator.comparingDouble((MovementOption option)->
                            -(-option.motionX()*remaining.x+option.motionZ()*remaining.z)/Math.hypot(option.motionX(),option.motionZ()))).toList();
            for (var option:options) {
                double length=Math.hypot(option.motionX(),option.motionZ());
                if ((-option.motionX()*remaining.x+option.motionZ()*remaining.z)/length<remaining.horizontalDistance()*.35) continue;
                double dx=-option.motionX()/length*naturalMiningSpeed()/.454+ctx.player().getDeltaMovement().x/.454;
                double dz=option.motionZ()/length*naturalMiningSpeed()/.454+ctx.player().getDeltaMovement().z/.454;
                if (!naturalFloorStepSafe(dx,dz)) continue;
                baritone.getInputOverrideHandler().setInputForceState(option.input1(),true);
                if (option.input2()!=null) baritone.getInputOverrideHandler().setInputForceState(option.input2(),true);
                return true;
            }
            naturalApproaching=false;
            return false;
        }
        return false;
    }

    private double naturalWorkingRadius() {
        return naturalBatch==null ? 1.5 : Math.max(1.5,1+naturalBatch.size()*.6);
    }

    private double naturalMiningSpeed() {
        double speed=ctx.player().getSpeed();
        if (shouldSneakForPrecision() || ctx.player().isCrouching())
            speed*=Math.max(.05,Math.min(1,ctx.player().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SNEAKING_SPEED)));
        return speed;
    }

    /** Kept separate so the world-based guard probe can test each physical key. */
    private boolean naturalNudgeSafe(Input input,List<BlockPos> ores) {
        double yaw=Math.toRadians(ctx.player().getYRot());
        double dx=-Math.sin(yaw),dz=Math.cos(yaw);
        if (input==Input.MOVE_BACK) {dx=-dx;dz=-dz;}
        if (input==Input.MOVE_LEFT) {dx=Math.cos(yaw);dz=Math.sin(yaw);}
        if (input==Input.MOVE_RIGHT) {dx=-Math.cos(yaw);dz=-Math.sin(yaw);}
        return naturalStepSafe(dx,dz,ores);
    }

    private boolean naturalStepSafe(double directionX,double directionZ,List<BlockPos> ores) {
        var velocity=ctx.player().getDeltaMovement();
        double impulse=naturalMiningSpeed()/.454;
        double dx=directionX*impulse+velocity.x/.454,dz=directionZ*impulse+velocity.z/.454;
        var future=ctx.player().position().add(dx,0,dz);
        if (Math.hypot(future.x-naturalStance.getX()-.5,future.z-naturalStance.getZ()-.5)>naturalWorkingRadius()-.1) return false;
        var eye=ctx.playerHead().add(dx,0,dz);
        double reach=ctx.playerController().getBlockReachDistance()-.15;
        if (ores.stream().anyMatch(ore->eye.distanceToSqr(VecUtils.getBlockPosCenter(ore))>reach*reach
                || Math.hypot(future.x-ore.getX()-.5,future.z-ore.getZ()-.5)<1.8)) return false;
        return naturalFloorStepSafe(dx,dz);
    }

    private boolean naturalFloorStepSafe(double dx,double dz) {
        var box=ctx.player().getBoundingBox().expandTowards(dx,0,dz);
        return ctx.world().noCollision(ctx.player(),box) && naturalFloorSupported(dx,dz);
    }

    /** Solid, harmless ground under the body's sweep, whether or not something stops it on the way. */
    boolean naturalFloorSupported(double dx,double dz) {
        var box=ctx.player().getBoundingBox().expandTowards(dx,0,dz);
        for (int x=(int)Math.floor(box.minX);x<=Math.floor(box.maxX);x++) for (int z=(int)Math.floor(box.minZ);z<=Math.floor(box.maxZ);z++) {
            var floor=new BlockPos(x,ctx.playerFeet().getY()-1,z);
            var state=ctx.world().getBlockState(floor);
            if (!state.isFaceSturdy(ctx.world(),floor,Direction.UP) || MovementHelper.avoidWalkingInto(state)
                    || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)
                    || state.is(Blocks.BLUE_ICE) || state.is(Blocks.SLIME_BLOCK) || state.is(Blocks.HONEY_BLOCK)) return false;
        }
        return true;
    }


    private void updateLoucaSystem() {
        Map<BlockPos, Long> copy = new HashMap<>(anticipatedDrops);
        ctx.getSelectedBlock().ifPresent(pos -> {
            if (knownOreLocations.contains(pos)) {
                long loiter = Baritone.settings().mineDropLoiterDurationMSThanksLouca.value;
                if (Baritone.settings().naturalMovement.value) loiter = Math.max(loiter, 750L);
                copy.put(pos, System.currentTimeMillis() + loiter);
            }
        });
        // elaborate dance to avoid concurrentmodificationexcepption since rescan thread reads this
        // don't want to slow everything down with a gross lock do we now
        for (BlockPos pos : anticipatedDrops.keySet()) {
            if (copy.get(pos) < System.currentTimeMillis()) {
                copy.remove(pos);
            }
        }
        anticipatedDrops = copy;
    }

    @Override
    public void onLostControl() {
        if (isHoldingAttack() && ctx.player() != null) ctx.playerController().resetBlockRemoving();
        baritone.getInputOverrideHandler().clearAllKeys();
        mine(0, (BlockOptionalMetaLookup) null);
    }

    @Override
    public String displayName0() {
        return "Mine " + filter;
    }

    private PathingCommand updateGoal() {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return null;
        }

        boolean legit = Baritone.settings().legitMine.value;
        List<BlockPos> locs = knownOreLocations;
        if (!locs.isEmpty()) {
            CalculationContext context = new CalculationContext(baritone);
            List<BlockPos> locs2 = prune(context, new ArrayList<>(locs), filter, Baritone.settings().mineMaxOreLocationsCount.value, blacklist, droppedItemsScan());
            // can't reassign locs, gotta make a new var locs2, because we use it in a lambda right here, and variables you use in a lambda must be effectively final
            Goal goal = new GoalComposite(locs2.stream().map(loc -> coalesce(loc, locs2, context)).toArray(Goal[]::new));
            knownOreLocations = locs2;
            return new PathingCommand(goal, legit ? PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH : PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        // we don't know any ore locations at the moment
        if (treeJob || !legit && !Baritone.settings().exploreForBlocks.value) {
            return null;
        }
        // only when we should explore for blocks or are in legit mode we do this
        int y = Baritone.settings().legitMineYLevel.value;
        if (branchPoint == null) {
            /*if (!baritone.getPathingBehavior().isPathing() && playerFeet().y == y) {
                // cool, path is over and we are at desired y
                branchPoint = playerFeet();
                branchPointRunaway = null;
            } else {
                return new GoalYLevel(y);
            }*/
            branchPoint = ctx.playerFeet();
        }
        // TODO shaft mode, mine 1x1 shafts to either side
        // TODO also, see if the GoalRunAway with maintain Y at 11 works even from the surface
        if (branchPointRunaway == null) {
            branchPointRunaway = new GoalRunAway(1, y, branchPoint) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    return false;
                }

                @Override
                public double heuristic() {
                    return Double.NEGATIVE_INFINITY;
                }
            };
        }
        return new PathingCommand(branchPointRunaway, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private void rescan(List<BlockPos> already, CalculationContext context) {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return;
        }
        if (Baritone.settings().legitMine.value) {
            return;
        }
        List<BlockPos> dropped = droppedItemsScan();
        List<BlockPos> locs = searchWorld(context, filter, Baritone.settings().mineMaxOreLocationsCount.value, already, blacklist, dropped);
        locs.addAll(dropped);
        if (locs.isEmpty() && (treeJob || !Baritone.settings().exploreForBlocks.value)) {
            logDirect("No locations for " + filter + " known, cancelling");
            if (Baritone.settings().notificationOnMineFail.value) {
                logNotification("No locations for " + filter + " known, cancelling", true);
            }
            cancel();
            return;
        }
        knownOreLocations = locs;
    }

    private boolean internalMiningGoal(BlockPos pos, CalculationContext context, List<BlockPos> locs) {
        // Here, BlockStateInterface is used because the position may be in a cached chunk (the targeted block is one that is kept track of)
        if (locs.contains(pos)) {
            return true;
        }
        BlockState state = context.bsi.get0(pos);
        if (Baritone.settings().internalMiningAirException.value && state.getBlock() instanceof AirBlock) {
            return true;
        }
        return filter.has(state) && plausibleToBreak(context, pos);
    }

    private Goal coalesce(BlockPos loc, List<BlockPos> locs, CalculationContext context) {
        boolean assumeVerticalShaftMine = !(baritone.bsi.get0(loc.above()).getBlock() instanceof FallingBlock);
        if (!Baritone.settings().forceInternalMining.value) {
            if (assumeVerticalShaftMine) {
                // we can get directly below the block
                return new GoalThreeBlocks(loc);
            } else {
                // we need to get feet or head into the block
                return new GoalTwoBlocks(loc);
            }
        }
        boolean upwardGoal = internalMiningGoal(loc.above(), context, locs);
        boolean downwardGoal = internalMiningGoal(loc.below(), context, locs);
        boolean doubleDownwardGoal = internalMiningGoal(loc.below(2), context, locs);
        if (upwardGoal == downwardGoal) { // symmetric
            if (doubleDownwardGoal && assumeVerticalShaftMine) {
                // we have a checkerboard like pattern
                // this one, and the one two below it
                // therefore it's fine to path to immediately below this one, since your feet will be in the doubleDownwardGoal
                // but only if assumeVerticalShaftMine
                return new GoalThreeBlocks(loc);
            } else {
                // this block has nothing interesting two below, but is symmetric vertically so we can get either feet or head into it
                return new GoalTwoBlocks(loc);
            }
        }
        if (upwardGoal) {
            // downwardGoal known to be false
            // ignore the gap then potential doubleDownward, because we want to path feet into this one and head into upwardGoal
            return new GoalBlock(loc);
        }
        // upwardGoal known to be false, downwardGoal known to be true
        if (doubleDownwardGoal && assumeVerticalShaftMine) {
            // this block and two below it are goals
            // path into the center of the one below, because that includes directly below this one
            return new GoalTwoBlocks(loc.below());
        }
        // upwardGoal false, downwardGoal true, doubleDownwardGoal false
        // just this block and the one immediately below, no others
        return new GoalBlock(loc.below());
    }

    private static class GoalThreeBlocks extends GoalTwoBlocks {

        public GoalThreeBlocks(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return x == this.x && (y == this.y || y == this.y - 1 || y == this.y - 2) && z == this.z;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int xDiff = x - this.x;
            int yDiff = y - this.y;
            int zDiff = z - this.z;
            return GoalBlock.calculate(xDiff, yDiff < -1 ? yDiff + 2 : yDiff == -1 ? 0 : yDiff, zDiff);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 393857768;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalThreeBlocks{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public List<BlockPos> droppedItemsScan() {
        if (!Baritone.settings().mineScanDroppedItems.value) {
            return Collections.emptyList();
        }
        List<BlockPos> ret = new ArrayList<>();
        for (Entity entity : ((ClientLevel) ctx.world()).entitiesForRendering()) {
            if (entity instanceof ItemEntity) {
                ItemEntity ei = (ItemEntity) entity;
                if (filter.has(ei.getItem())) {
                    ret.add(entity.blockPosition());
                }
            }
        }
        ret.addAll(anticipatedDrops.keySet());
        return ret;
    }

    public static List<BlockPos> searchWorld(CalculationContext ctx, BlockOptionalMetaLookup filter, int max, List<BlockPos> alreadyKnown, List<BlockPos> blacklist, List<BlockPos> dropped) {
        List<BlockPos> locs = new ArrayList<>();
        List<Block> untracked = new ArrayList<>();
        for (BlockOptionalMeta bom : filter.blocks()) {
            Block block = bom.getBlock();
            if (CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.contains(block)) {
                BetterBlockPos pf = ctx.baritone.getPlayerContext().playerFeet();

                // maxRegionDistanceSq 2 means adjacent directly or adjacent diagonally; nothing further than that
                locs.addAll(ctx.worldData.getCachedWorld().getLocationsOf(
                        BlockUtils.blockToString(block),
                        Baritone.settings().maxCachedWorldScanCount.value,
                        pf.x,
                        pf.z,
                        2
                ));
            } else {
                untracked.add(block);
            }
        }

        locs = prune(ctx, locs, filter, max, blacklist, dropped);

        if (Baritone.settings().naturalMovement.value) {
            // A full cache can consist of distant underground ore. Refresh
            // loaded candidates before truncation so nearby visible veins are
            // not excluded merely because another chunk filled the budget.
            locs.addAll(FasterWorldScanner.INSTANCE.scanNearestLoaded(
                    ctx.getBaritone().getPlayerContext(),filter,max,32));
        } else if (!untracked.isEmpty() || (Baritone.settings().extendCacheOnThreshold.value && locs.size() < max)) {
            locs.addAll(BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(
                    ctx.getBaritone().getPlayerContext(),
                    filter,
                    max,
                    10,
                    32
            )); // maxSearchRadius is NOT sq
        }

        locs.addAll(alreadyKnown);

        return prune(ctx, locs, filter, max, blacklist, dropped);
    }

    private boolean addNearby() {
        List<BlockPos> dropped = droppedItemsScan();
        knownOreLocations.addAll(dropped);
        BlockPos playerFeet = ctx.playerFeet();
        BlockStateInterface bsi = new BlockStateInterface(ctx);


        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return false;
        }

        int searchDist = 10;
        double fakedBlockReachDistance = 20; // at least 10 * sqrt(3) with some extra space to account for positioning within the block
        for (int x = playerFeet.getX() - searchDist; x <= playerFeet.getX() + searchDist; x++) {
            for (int y = playerFeet.getY() - searchDist; y <= playerFeet.getY() + searchDist; y++) {
                for (int z = playerFeet.getZ() - searchDist; z <= playerFeet.getZ() + searchDist; z++) {
                    // crucial to only add blocks we can see because otherwise this
                    // is an x-ray and it'll get caught
                    if (filter.has(bsi.get0(x, y, z))) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if ((Baritone.settings().legitMineIncludeDiagonals.value && knownOreLocations.stream().anyMatch(ore -> ore.distSqr(pos) <= 2 /* sq means this is pytha dist <= sqrt(2) */)) || RotationUtils.reachable(ctx, pos, fakedBlockReachDistance).isPresent()) {
                            knownOreLocations.add(pos);
                        }
                    }
                }
            }
        }
        knownOreLocations = prune(new CalculationContext(baritone), knownOreLocations, filter, Baritone.settings().mineMaxOreLocationsCount.value, blacklist, dropped);
        return true;
    }

    private static List<BlockPos> prune(CalculationContext ctx, List<BlockPos> locs2, BlockOptionalMetaLookup filter, int max, List<BlockPos> blacklist, List<BlockPos> dropped) {
        dropped.removeIf(drop -> {
            for (BlockPos pos : locs2) {
                if (pos.distSqr(drop) <= 9 && filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) && MineProcess.plausibleToBreak(ctx, pos)) { // TODO maybe drop also has to be supported? no lava below?
                    return true;
                }
            }
            return false;
        });
        List<BlockPos> locs = locs2
                .stream()
                .distinct()

                // remove any that are within loaded chunks that aren't actually what we want
                .filter(pos -> !ctx.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ()) || filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) || dropped.contains(pos))

                // remove any that are implausible to mine (encased in bedrock, or touching lava)
                .filter(pos -> MineProcess.plausibleToBreak(ctx, pos))

                .filter(pos -> {
                    if (Baritone.settings().allowOnlyExposedOres.value) {
                        return isNextToAir(ctx, pos);
                    } else {
                        return true;
                    }
                })

                .filter(pos -> pos.getY() >= Baritone.settings().minYLevelWhileMining.value + ctx.world.dimensionType().minY())

                .filter(pos -> pos.getY() <= Baritone.settings().maxYLevelWhileMining.value)

                .filter(pos -> !blacklist.contains(pos))

                .sorted(Comparator.comparingDouble(ctx.getBaritone().getPlayerContext().player().blockPosition()::distSqr))
                .collect(Collectors.toList());

        if (locs.size() > max) {
            return locs.subList(0, max);
        }
        return locs;
    }

    public static boolean isNextToAir(CalculationContext ctx, BlockPos pos) {
        int radius = Baritone.settings().allowOnlyExposedOresDistance.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= radius
                            && MovementHelper.isTransparent(ctx.getBlock(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    public static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos) {
        BlockState state = ctx.bsi.get0(pos);
        if (MovementHelper.getMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(), state, true) >= COST_INF) {
            return false;
        }
        if (MovementHelper.avoidBreaking(ctx.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }

        // bedrock above and below makes it implausible, otherwise we're good
        return !(ctx.bsi.get0(pos.above()).getBlock() == Blocks.BEDROCK && ctx.bsi.get0(pos.below()).getBlock() == Blocks.BEDROCK);
    }

    @Override
    public void mineByName(int quantity, String... blocks) {
        mine(quantity, new BlockOptionalMetaLookup(blocks));
    }

    @Override
    public void mine(int quantity, BlockOptionalMetaLookup filter) {
        this.filter = filter;
        if (this.filterFilter() == null) {
            this.filter = null;
        }
        this.desiredQuantity = quantity;
        this.knownOreLocations = new ArrayList<>();
        this.blacklist = new ArrayList<>();
        this.branchPoint = null;
        this.branchPointRunaway = null;
        this.anticipatedDrops = new HashMap<>();
        this.naturalBatch = null;
        this.naturalStance = this.naturalTarget = null;
        this.naturalDropArea = Collections.emptyList();
        this.naturalCollecting = false;
        this.naturalAttackHeld = false;
        this.naturalViewPitch = null;
        this.naturalRetryAfter = 0;
        this.naturalAimBlock=null;
        this.naturalAimFace=null;
        this.naturalAdvanceOre=this.naturalAdvanceNext=null;
        this.naturalAdvanceOrigin=this.naturalAdvancePoint=null;
        this.naturalApproachOre=null;
        this.naturalBatchId=0;
        this.naturalApproaching=this.naturalWorkingEstablished=false;
        this.naturalAcquireOre=null;
        this.naturalAcquireTick=-1;
        this.naturalAcquireTicks=0;
        this.treeJob=filter != null && !filter.blocks().isEmpty() && filter.blocks().stream()
                .allMatch(block -> block.getBlock().defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS));
        this.trees.reset();
        if (filter != null) {
            rescan(new ArrayList<>(), new CalculationContext(baritone));
        }
    }

    private BlockOptionalMetaLookup filterFilter() {
        if (this.filter == null) {
            return null;
        }
        if (!Baritone.settings().allowBreak.value) {
            BlockOptionalMetaLookup f = new BlockOptionalMetaLookup(this.filter.blocks()
                    .stream()
                    .filter(e -> Baritone.settings().allowBreakAnyway.value.contains(e.getBlock()))
                    .toArray(BlockOptionalMeta[]::new));
            if (f.blocks().isEmpty()) {
                logDirect("Unable to mine when allowBreak is false and target block is not in allowBreakAnyway!");
                return null;
            }
            return f;
        }
        return filter;
    }
}
