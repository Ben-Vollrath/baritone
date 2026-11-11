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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fights mobs the way a person does. Against mobs that come for the player it runs in, circles at about three blocks
 * with the view on one mob at a time, and keeps clicking at a steady rhythm whether or not the crosshair is on
 * anything. Against mobs that don't (Hypixel's low-level mobs wander and leave a player alone) it hunts: runs straight
 * at one, stays on it, and clicks faster the closer it gets, as the user did in the Hub Graveyard.
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

    private Set<EntityType<?>> types = Set.of();
    private double range;
    /** Each mob's last positions, newest last, to tell a chasing mob from a wandering one. */
    private final Map<Integer, ArrayDeque<double[]>> sightings = new HashMap<>();
    private int lastChaseTick, huntLeft, progressTicks, pathUntil;
    private int[] huntKeys = {0, 0};
    private Integer huntTarget;
    private double bestRange;
    /** Why ticks went the way they did, for tuning against recordings (dev only). */
    public static final Map<String, Integer> DEBUG = new java.util.concurrent.ConcurrentHashMap<>();

    private static void count(String what) {
        DEBUG.merge(what, 1, Integer::sum);
    }
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

    /** Mobs further above or below than this are never fought (the crypt's Lv33 ghouls under the Hub Graveyard). */
    private static final double MAX_HEIGHT_DIFFERENCE = 5;
    /** A chasing mob this close, seen this recently, means the fight is the circling kind. */
    private static final double CHASE_RANGE = 8;
    private static final int CHASE_MEMORY = 40;
    /** Hunting: no closer to the target by this much in this many ticks hands the approach to the pathfinder. */
    private static final double HUNT_PROGRESS = .5;
    private static final int HUNT_PATIENCE = 30;
    /** How long the pathfinder keeps an approach it was given, in ticks. */
    private static final int PATH_TICKS = 40;
    /** Hunting stays on its target while it is this close. */
    private static final double HUNT_STICK = 6;

    /** Fights mobs of this type within range of the player until none are left in range. */
    public void fight(EntityType<?> type, double range) {
        fight(Set.of(type), range);
    }

    /** Fights mobs of any of these types (say zombies and zombie villagers) within range, until none are left. */
    public void fight(Collection<EntityType<?>> types, double range) {
        this.types = Set.copyOf(types);
        this.range = range;
        sightings.clear();
        lastChaseTick = -CHASE_MEMORY;
        huntTarget = null;
        huntLeft = 0;
        bestRange = Double.MAX_VALUE;
        progressTicks = 0;
        pathUntil = 0;
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
        boolean hunting = ctx.player().tickCount - lastChaseTick > CHASE_MEMORY;
        count(hunting ? "tick hunting" : "tick circling");
        Mob focus = hunting ? huntTarget(mobs, x, z) : nearest;
        Entity focusEntity = ctx.world().getEntity(focus.id());
        boolean direct = focus.range(x, z) <= DIRECT_RANGE && focusEntity != null && ctx.player().hasLineOfSight(focusEntity);
        // Hunting and getting no closer (a gravestone, a fence, a step in the way): let the pathfinder take it there
        if (hunting && direct && focus.range(x, z) > MobFightModel.REACH) {
            if (focus.range(x, z) < bestRange - HUNT_PROGRESS) {
                bestRange = focus.range(x, z);
                progressTicks = 0;
            } else if (++progressTicks > HUNT_PATIENCE) {
                direct = false;
            }
        } else {
            bestRange = focus.range(x, z);
            progressTicks = 0;
        }
        int now = ctx.player().tickCount;
        // Once the pathfinder has the approach it keeps it for a while: deciding afresh every tick handed it over for
        // one tick and took it back the next, which cancelled the path and left the player standing (2026-11-11)
        boolean inReach = direct && focus.range(x, z) <= MobFightModel.REACH;
        if (!direct && isSafeToCancel && now >= pathUntil) {
            pathUntil = now + PATH_TICKS;
            progressTicks = 0;
            bestRange = focus.range(x, z);
        }
        if (now < pathUntil && !inReach && isSafeToCancel) {
            count(direct ? "path: holding" : "path: not direct");
            release();
            stopMoving();
            return new PathingCommand(new GoalNear(focusEntity != null ? focusEntity.blockPosition() : ctx.playerFeet(), 2),
                    PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        pathUntil = Math.min(pathUntil, now);
        aim(mobs, x, z, hunting ? focus : null);
        if (hunting) {
            huntClick(focus.range(x, z), mobs);
            huntMove(mobs, focus, x, z);
        } else {
            click(nearest.range(x, z), mobs);
            move(mobs, nearest, x, z);
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private List<Mob> scan() {
        var mobs = new ArrayList<Mob>();
        int now = ctx.player().tickCount;
        double px = ctx.player().getX(), pz = ctx.player().getZ();
        ctx.entitiesStream().filter(this::wanted).forEach(entity -> {
            mobs.add(new Mob(entity.getId(), entity.getX(), entity.getZ(), ((LivingEntity) entity).hurtTime));
            // Remember where it was, to see whether it walks at the player by itself
            var seen = sightings.computeIfAbsent(entity.getId(), id -> new ArrayDeque<>());
            seen.addLast(new double[]{now, entity.getX(), entity.getZ()});
            while (seen.size() > MobFightModel.CHASE_TICKS + 1) seen.removeFirst();
            double[] then = seen.peekFirst();
            if (seen.size() > MobFightModel.CHASE_TICKS && Math.hypot(entity.getX() - px, entity.getZ() - pz) <= CHASE_RANGE
                    && MobFightModel.closing(then[1], then[2], entity.getX(), entity.getZ(), px, pz) >= MobFightModel.CHASE_CLOSING) {
                lastChaseTick = now;
            }
        });
        if (now % 100 == 0) sightings.keySet().removeIf(id -> mobs.stream().noneMatch(mob -> mob.id() == id));
        return mobs;
    }

    private boolean wanted(Entity entity) {
        return entity != ctx.player() && types.contains(entity.getType()) && entity instanceof LivingEntity living && living.isAlive()
                && entity.distanceToSqr(ctx.player()) <= range * range
                && Math.abs(entity.getY() - ctx.player().getY()) <= MAX_HEIGHT_DIFFERENCE;
    }

    /** Hunting stays on one mob until it dies or gets away, the way the user hit about four times in a row. */
    private Mob huntTarget(List<Mob> mobs, double x, double z) {
        if (huntTarget != null) {
            for (Mob mob : mobs) if (mob.id() == huntTarget && mob.range(x, z) <= HUNT_STICK) return mob;
        }
        Mob chosen = MobFightModel.chooseTarget(mobs, null, x, z, ctx.player().getYRot());
        huntTarget = chosen.id();
        huntLeft = 0;
        return chosen;
    }

    private void huntClick(double range, List<Mob> mobs) {
        var key = ctx.minecraft().options.keyAttack;
        HitResult hit = crosshair();
        boolean safe = safeToClick(hit, mobs);
        if (keyDown && (--pressLeft <= 0 || !safe)) release();
        if (range > MobFightModel.HUNT_CLICK_FROM) { count("hunt click: out of range"); return; }
        if (--clickWait > 0) return;
        clickWait = MobFightModel.huntClickGap(range, random);
        if (!safe) count("hunt click: unsafe " + (hit instanceof net.minecraft.world.phys.BlockHitResult b
                ? ctx.world().getBlockState(b.getBlockPos()).getBlock().toString() : String.valueOf(hit == null ? null : hit.getType())));
        if (!safe || ctx.minecraft().gui.screen() != null) return;
        count(hit != null && hit.getType() == HitResult.Type.ENTITY ? "hunt click: on mob" : "hunt click: air");
        KeyMapping.click(InputConstants.getKey(key.saveString()));
        key.setDown(true);
        keyDown = true;
        pressLeft = MobFightModel.pressTicks(random);
    }

    /** Hunting keys: straight in with sprint, then mostly W or nothing at the mob, a little A/D, nearly never S. */
    private void huntMove(List<Mob> mobs, Mob target, double x, double z) {
        double range = target.range(x, z);
        if (--huntLeft <= 0 || (range > 3 && huntKeys[0] <= 0)) {
            huntLeft = MobFightModel.huntRun(random);
            huntKeys = MobFightModel.huntKeys(range, random);
        }
        double yaw = Math.toRadians(ctx.player().getYRot());
        double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
        double leftX = Math.cos(yaw), leftZ = Math.sin(yaw);
        int back = huntKeys[0], sideNow = huntKeys[1];
        // Into a wall or off a ledge: the nearest keys that aren't
        if (!clear(forwardX * back + leftX * sideNow, forwardZ * back + leftZ * sideNow)) {
            back = 0;
            sideNow = 0;
            for (int[] fallback : new int[][]{{1, 1}, {1, -1}, {0, 1}, {0, -1}}) {
                if (clear(forwardX * fallback[0] + leftX * fallback[1], forwardZ * fallback[0] + leftZ * fallback[1])) {
                    back = fallback[0];
                    sideNow = fallback[1];
                    break;
                }
            }
            huntKeys = new int[]{back, sideNow};
            huntLeft = Math.max(huntLeft, 6);
        }
        var input = baritone.getInputOverrideHandler();
        input.setInputForceState(Input.MOVE_FORWARD, back > 0);
        input.setInputForceState(Input.MOVE_BACK, back < 0);
        input.setInputForceState(Input.MOVE_LEFT, sideNow > 0);
        input.setInputForceState(Input.MOVE_RIGHT, sideNow < 0);
        // The user sprinted nearly all the way in, and about half the time up close
        input.setInputForceState(Input.SPRINT, back > 0 && (range > 3 || random.nextDouble() < .5));
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

    private void aim(List<Mob> mobs, double x, double z, Mob forced) {
        double yaw = ctx.player().getYRot();
        Mob chosen = forced != null ? forced : MobFightModel.chooseTarget(mobs, target, x, z, yaw);
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
        double range = chosen.range(x, z);
        // Hunting a mob that stands still for it, the view goes on its body: the arena's rest-until-11-degrees and
        // 5-degree offset missed a zombie 2.5 blocks away (about 7 degrees either side) on every click in the Graveyard
        double drift = YAW_DRIFT, settled = YAW_SETTLED, offset = aimYaw;
        if (forced != null) {
            drift = MobFightModel.huntYawDrift(range);
            settled = drift * .4;
            offset = aimYaw * Math.min(1, range / 8);
        }
        double error = MobFightModel.wrap(bearing + offset - yaw);
        if (Math.abs(error) > drift) correcting = true;
        else if (Math.abs(error) < settled) correcting = false;
        double command = !correcting ? yaw
                : yaw + Math.max(-MAX_COMMAND, Math.min(MAX_COMMAND, (error + rate * yawLag * LEAD_SHARE) * yawGain));
        double pitch = MobFightModel.pitch(range) + aimPitch;
        Entity body = forced != null ? ctx.world().getEntity(chosen.id()) : null;
        if (body != null) {
            // From the eyes to the middle of its body, whatever the slope between them
            double drop = ctx.player().getEyeY() - (body.getY() + body.getBbHeight() * .55);
            pitch = MobFightModel.huntPitch(drop, range) + aimPitch * .4;
        }
        baritone.getLookBehavior().updateTarget(new Rotation((float) command, (float) pitch), false);
    }

    private void click(double nearest, List<Mob> mobs) {
        if (nearest <= clickFrom) clicking = true;
        var key = ctx.minecraft().options.keyAttack;
        HitResult hit = crosshair();
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

    /**
     * What a click would hit: the game's own crosshair target, entities included. Baritone's objectMouseOver is a
     * block-only ray at block reach, so with a gravestone behind a zombie in the Graveyard it saw the stone and the
     * fight refused about 300 clicks in three minutes with the zombie right in front (2026-11-11).
     */
    private HitResult crosshair() {
        return ctx.minecraft().hitResult;
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
        huntTarget = null;
        types = Set.of();
        sightings.clear();
        stopMoving();
        release();
    }

    @Override
    public void onLostControl() {
        finish();
    }

    @Override
    public String displayName0() {
        return "Fighting " + (types.isEmpty() ? "nothing" : types.stream().map(EntityType::toShortString).sorted().toList());
    }
}
