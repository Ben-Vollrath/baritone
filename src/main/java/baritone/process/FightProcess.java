/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.look.ForkableRandom;
import baritone.behavior.look.NaturalSteering;
import baritone.process.MobFightModel.Forward;
import baritone.process.MobFightModel.Mob;
import baritone.utils.BaritoneProcessHelper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Fights mobs the way a person does: it runs in, circles at about three blocks with the view on one
 * mob at a time, and keeps clicking at a steady rhythm whether or not the crosshair is on anything.
 * Clicks go through the ordinary attack key, so reach and timing are vanilla's. It never clicks when
 * the crosshair is on a block or another kind of entity, so a fight breaks nothing and hits nobody else.
 */
public final class FightProcess extends BaritoneProcessHelper {
    /** Where the pathfinder gives way to running straight in. */
    private static final double DIRECT_RANGE = 12;
    private static final double LOOK_AHEAD = 1.3, MOB_SPEED = .1, COAST = 1 / .454;
    /** Blocks per tick by key: sprinting forward, strafing with or without forward, and backing. */
    private static final double SPRINT_SPEED = .27, STRAFE_SPEED = .215, BACK_SPEED = .2;
    /** How far ahead a step is checked, and the room to a mob below which a different step gets chosen. */
    private static final int CLEARANCE_TICKS = 8;
    private static final double SAFE_ROOM = 1.25;
    /** Keys to try instead of the rolled ones, as forward (+1 W, -1 S) and side relative to the rolled strafe. S comes last: the recordings hardly used it. */
    private static final int[][] FALLBACKS = {{0, 1}, {0, -1}, {1, 1}, {1, -1}, {1, 0}, {-1, 1}, {-1, -1}, {-1, 0}};
    /** A command this far around or more would wrap past the view and read as no error at all. */
    private static final double MAX_COMMAND = 170;
    /** Motor stiffness the camera's yaw gets in a fight, whatever the job's seed gives, and how much of the lag it leads by. */
    private static final double FIGHT_STIFFNESS = .42, LEAD_SHARE = .5, MAX_BEARING_RATE = 15;
    /**
     * The view rests until the mob has drifted this far, then corrects in one motion until it is this close.
     * A person holds the view and flicks, rather than following every step the mob takes.
     */
    private static final double YAW_DRIFT = 11, YAW_SETTLED = 4;
    private static final int IDLE_TICKS = 20, RETARGET_TICKS = 40;
    private static final double PITCH_SPREAD = 2.5, YAW_SPREAD = 5;

    private EntityType<?> type;
    private double range;
    private boolean active;

    private ForkableRandom random;
    private int reaction, idle, clickWait, pressLeft, strafeLeft, forwardLeft, targetTicks;
    private int side;
    private Forward forward = Forward.NONE;
    private boolean clicking, strafing, keyDown, correcting;
    private double clickFrom, aimPitch, aimYaw, yawGain, yawLag, lastBearing;
    private Integer target;

    public FightProcess(Baritone baritone) {
        super(baritone);
    }

    /** Fights mobs of this type within range of the player until none are left in range. */
    public void fight(EntityType<?> type, double range) {
        this.type = type;
        this.range = range;
        active = true;
        random = new ForkableRandom(Baritone.settings().naturalMovementSeed.value ^ 0x51c3a9e0b7d24f16L);
        reaction = MobFightModel.reactionTicks(random);
        clickFrom = MobFightModel.clickFrom(random);
        side = random.nextDouble() < .5 ? 1 : -1;
        var motor = NaturalSteering.parameters(Baritone.settings().naturalMovementSeed.value,
                Baritone.settings().smoothMovementMaxTurn.value, Baritone.settings().smoothMovementAcceleration.value);
        yawGain = FIGHT_STIFFNESS / motor.stiffness();
        yawLag = motor.damping() / FIGHT_STIFFNESS;
        idle = clickWait = pressLeft = strafeLeft = forwardLeft = targetTicks = 0;
        forward = Forward.NONE;
        clicking = strafing = correcting = false;
        target = null;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (ctx.player() == null || !ctx.player().isAlive()) {
            finish();
            return null;
        }
        List<Mob> mobs = scan();
        if (mobs.isEmpty()) {
            stopMoving();
            release();
            if (++idle >= IDLE_TICKS) {
                finish();
                return null;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        idle = 0;
        double x = ctx.player().getX(), z = ctx.player().getZ();
        Mob nearest = MobFightModel.nearest(mobs, x, z);
        if (reaction > 0) {
            reaction--;
            stopMoving();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        holdWeapon();
        Entity nearestEntity = ctx.world().getEntity(nearest.id());
        boolean direct = nearest.range(x, z) <= DIRECT_RANGE && nearestEntity != null && ctx.player().hasLineOfSight(nearestEntity);
        if (!direct && isSafeToCancel) {
            release();
            stopMoving();
            return new PathingCommand(new GoalNear(nearestEntity != null ? nearestEntity.blockPosition() : ctx.playerFeet(), 2),
                    PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        aim(mobs, x, z);
        click(nearest.range(x, z), mobs);
        move(mobs, nearest, x, z);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private List<Mob> scan() {
        var mobs = new ArrayList<Mob>();
        ctx.entitiesStream().filter(this::wanted).forEach(entity ->
                mobs.add(new Mob(entity.getId(), entity.getX(), entity.getZ(), ((LivingEntity) entity).hurtTime)));
        return mobs;
    }

    private boolean wanted(Entity entity) {
        return entity != ctx.player() && entity.getType() == type && entity instanceof LivingEntity living && living.isAlive()
                && entity.distanceToSqr(ctx.player()) <= range * range;
    }

    /** A sword if one is on the hotbar; otherwise whatever is already in hand. */
    private void holdWeapon() {
        var inventory = ctx.player().getInventory();
        if (inventory.getSelectedItem().is(ItemTags.SWORDS)) return;
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).is(ItemTags.SWORDS)) {
                inventory.setSelectedSlot(slot);
                return;
            }
        }
    }

    private void aim(List<Mob> mobs, double x, double z) {
        double yaw = ctx.player().getYRot();
        Mob chosen = MobFightModel.chooseTarget(mobs, target, x, z, yaw);
        if (target == null || chosen.id() != target || ++targetTicks > RETARGET_TICKS) {
            boolean sameMob = target != null && chosen.id() == target;
            target = chosen.id();
            targetTicks = 0;
            aimPitch = (random.nextDouble() * 2 - 1) * PITCH_SPREAD;
            aimYaw = (random.nextDouble() * 2 - 1) * YAW_SPREAD;
            if (!sameMob) {
                lastBearing = MobFightModel.bearing(chosen, x, z);
                correcting = true;
            }
        }
        double bearing = MobFightModel.bearing(chosen, x, z);
        // The motor trails a moving target by its lag, and a person leads it instead.
        double rate = Math.max(-MAX_BEARING_RATE, Math.min(MAX_BEARING_RATE, MobFightModel.wrap(bearing - lastBearing)));
        lastBearing = bearing;
        double error = MobFightModel.wrap(bearing + aimYaw - yaw);
        if (Math.abs(error) > YAW_DRIFT) correcting = true;
        else if (Math.abs(error) < YAW_SETTLED) correcting = false;
        double command = !correcting ? yaw
                : yaw + Math.max(-MAX_COMMAND, Math.min(MAX_COMMAND, (error + rate * yawLag * LEAD_SHARE) * yawGain));
        double pitch = MobFightModel.pitch(chosen.range(x, z)) + aimPitch;
        baritone.getLookBehavior().updateTarget(new Rotation((float) command, (float) pitch), false);
    }

    private void click(double nearest, List<Mob> mobs) {
        if (nearest <= clickFrom) clicking = true;
        var key = ctx.minecraft().options.keyAttack;
        HitResult hit = ctx.objectMouseOver();
        boolean safe = safeToClick(hit, mobs);
        if (keyDown && (--pressLeft <= 0 || !safe)) release();
        if (!clicking || --clickWait > 0) return;
        clickWait = MobFightModel.clickGap(random);
        if (!safe || ctx.minecraft().gui.screen() != null) return;
        KeyMapping.click(InputConstants.getKey(key.saveString()));
        key.setDown(true);
        keyDown = true;
        pressLeft = MobFightModel.pressTicks(random);
    }

    /** Empty air is a harmless swing. A block would start breaking, and another entity would get hit. */
    private boolean safeToClick(HitResult hit, List<Mob> mobs) {
        if (hit == null || hit.getType() == HitResult.Type.MISS) return true;
        if (!(hit instanceof EntityHitResult entity)) return false;
        for (Mob mob : mobs) if (mob.id() == entity.getEntity().getId()) return true;
        return false;
    }

    private void move(List<Mob> mobs, Mob nearest, double x, double z) {
        double range = nearest.range(x, z);
        int crowd = MobFightModel.crowd(mobs, x, z);
        double yaw = Math.toRadians(ctx.player().getYRot());
        double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
        double leftX = Math.cos(yaw), leftZ = Math.sin(yaw);

        if (--strafeLeft <= 0) {
            strafeLeft = MobFightModel.strafeRun(random);
            strafing = MobFightModel.strafes(range, random);
            double left = room(mobs, x, z, forwardX, forwardZ, leftX, leftZ, 0, 1);
            double right = room(mobs, x, z, forwardX, forwardZ, leftX, leftZ, 0, -1);
            side = MobFightModel.nextSide(side, left, right, random);
        }
        if (--forwardLeft <= 0) {
            forwardLeft = MobFightModel.forwardRun(random);
            forward = MobFightModel.forwardKey(range, crowd, random);
        }
        int sideNow = strafing ? side : 0;
        int back = forward == Forward.BACK ? -1 : forward == Forward.FORWARD ? 1 : 0;
        // The rolled keys stand unless they lead into a wall or too close to a mob.
        if (!clear(forwardX * back + leftX * sideNow, forwardZ * back + leftZ * sideNow)
                || room(mobs, x, z, forwardX, forwardZ, leftX, leftZ, back, sideNow) < SAFE_ROOM) {
            double best = -Double.MAX_VALUE;
            int bestBack = 0, bestSide = 0;
            int preferred = sideNow != 0 ? sideNow : side;
            for (int[] fallback : FALLBACKS) {
                int b = fallback[0], s = fallback[1] * preferred;
                if (!clear(forwardX * b + leftX * s, forwardZ * b + leftZ * s)) continue;
                double room = room(mobs, x, z, forwardX, forwardZ, leftX, leftZ, b, s);
                if (room >= SAFE_ROOM) {
                    bestBack = b;
                    bestSide = s;
                    break;
                }
                if (room > best) {
                    best = room;
                    bestBack = b;
                    bestSide = s;
                }
            }
            back = bestBack;
            sideNow = bestSide;
            forward = back > 0 ? Forward.FORWARD : back < 0 ? Forward.BACK : Forward.NONE;
            forwardLeft = Math.max(forwardLeft, 6);
            if (sideNow != 0) {
                side = sideNow;
                strafing = true;
                strafeLeft = Math.max(strafeLeft, 8);
            }
        }
        var input = baritone.getInputOverrideHandler();
        input.setInputForceState(Input.MOVE_FORWARD, back > 0);
        input.setInputForceState(Input.MOVE_BACK, back < 0);
        input.setInputForceState(Input.MOVE_LEFT, sideNow > 0);
        input.setInputForceState(Input.MOVE_RIGHT, sideNow < 0);
        input.setInputForceState(Input.SPRINT, back > 0);
    }

    private static double room(List<Mob> mobs, double x, double z, double forwardX, double forwardZ, double leftX, double leftZ, int back, int side) {
        double dirX = forwardX * back + leftX * side, dirZ = forwardZ * back + leftZ * side;
        double size = Math.hypot(dirX, dirZ);
        if (size < 1e-6) return MobFightModel.clearance(mobs, x, z, 0, 0, CLEARANCE_TICKS, MOB_SPEED);
        double speed = back > 0 ? SPRINT_SPEED : back < 0 ? BACK_SPEED : STRAFE_SPEED;
        return MobFightModel.clearance(mobs, x, z, dirX / size * speed, dirZ / size * speed, CLEARANCE_TICKS, MOB_SPEED);
    }

    /** Whether the body can go this way for the next moment, on solid floor. */
    private boolean clear(double dirX, double dirZ) {
        double size = Math.hypot(dirX, dirZ);
        if (size < 1e-6) return true;
        Vec3 motion = ctx.player().getDeltaMovement();
        double stepX = dirX / size * LOOK_AHEAD + motion.x * COAST, stepZ = dirZ / size * LOOK_AHEAD + motion.z * COAST;
        var box = ctx.player().getBoundingBox().move(stepX, 0, stepZ);
        return ctx.world().noCollision(ctx.player(), box) && baritone.getMineProcess().naturalFloorSupported(stepX, stepZ);
    }

    private void stopMoving() {
        var input = baritone.getInputOverrideHandler();
        input.setInputForceState(Input.MOVE_FORWARD, false);
        input.setInputForceState(Input.MOVE_BACK, false);
        input.setInputForceState(Input.MOVE_LEFT, false);
        input.setInputForceState(Input.MOVE_RIGHT, false);
        input.setInputForceState(Input.SPRINT, false);
    }

    private void release() {
        if (keyDown) ctx.minecraft().options.keyAttack.setDown(false);
        keyDown = false;
        pressLeft = 0;
    }

    private void finish() {
        active = false;
        target = null;
        type = null;
        stopMoving();
        release();
    }

    @Override
    public void onLostControl() {
        finish();
    }

    @Override
    public String displayName0() {
        return "Fighting " + (type == null ? "nothing" : type.toShortString());
    }
}
