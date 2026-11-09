/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.behavior.look.ForkableRandom;

import java.util.List;

/** Decisions behind {@link FightProcess} that need no game. The numbers come from five recorded zombie fights. */
final class MobFightModel {
    private MobFightModel() {}

    enum Forward { NONE, FORWARD, BACK }

    /** A living mob as the fight sees it: {@code hurtTime} counts down from 10 after a hit. */
    record Mob(int id, double x, double z, int hurtTime) {
        double range(double fromX, double fromZ) {
            return Math.hypot(x - fromX, z - fromZ);
        }
    }

    /** Reach from the eyes is 3, and a mob's body starts .3 before its middle. */
    static final double REACH = 3.2;
    /** How far from the player a mob counts as part of the crowd. */
    static final double CROWD_RANGE = 4;
    private static final double SWITCH_REACH = 3.8, TURN_WEIGHT = 1 / 150d, STICK = .2, FLINCH_PENALTY = .8;
    /** Pitch at a hit stayed between about 5 and 13 degrees at any range. */
    private static final double LOW_PITCH = 5, HIGH_PITCH = 13, AIM_DROP = .52;

    /** The clicks were 2 or 3 ticks apart almost evenly, with the odd longer gap. */
    static int clickGap(ForkableRandom random) {
        double roll = random.nextDouble();
        return roll < .47 ? 2 : roll < .95 ? 3 : roll < .98 ? 4 : 5;
    }

    /** Most presses lasted one tick and about a fifth lasted two. */
    static int pressTicks(ForkableRandom random) {
        double roll = random.nextDouble();
        return roll < .74 ? 1 : roll < .97 ? 2 : 3;
    }

    /** How far from the nearest mob clicking started: 3.2 to 5.9 blocks. */
    static double clickFrom(ForkableRandom random) {
        return 3.2 + random.nextDouble() * 2.7;
    }

    /** Ticks after the start before the first key: 1 to 18, usually about 6. */
    static int reactionTicks(ForkableRandom random) {
        double roll = random.nextDouble();
        return 1 + (int) (roll * roll * 18);
    }

    /** Strafe runs: median 21 ticks, tenth percentile 6, ninetieth 49, longest 100. */
    static int strafeRun(ForkableRandom random) {
        double u1 = Math.max(1e-9, random.nextDouble()), u2 = random.nextDouble();
        double normal = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
        return (int) Math.max(4, Math.min(100, Math.round(21 * Math.exp(.72 * normal))));
    }

    /** Share of ticks with W down, by the nearest mob's range and how many mobs are close. */
    static double forwardShare(double nearest, int crowd) {
        if (nearest >= 4) return .8;
        if (nearest >= 3) return .8;
        double byRange = nearest >= 2.5 ? .5 : nearest >= 2 ? .2 : 0;
        return crowd >= 3 ? Math.min(byRange, .18) : byRange;
    }

    /** Share of ticks with S down. Nearly never, except with a mob nearly on top. */
    static double backShare(double nearest) {
        return nearest >= 3 ? .04 : nearest >= 2 ? .08 : .3;
    }

    /** Beyond this range the recordings ran in rather than circled, half of the time without strafing at all. */
    static boolean strafes(double nearest, ForkableRandom random) {
        return nearest < 4.5 || random.nextDouble() < .5;
    }

    static Forward forwardKey(double nearest, int crowd, ForkableRandom random) {
        double roll = random.nextDouble(), forward = forwardShare(nearest, crowd), back = backShare(nearest);
        return roll < forward ? Forward.FORWARD : roll < forward + back ? Forward.BACK : Forward.NONE;
    }

    /** W and S runs are short: a few ticks at a time. */
    static int forwardRun(ForkableRandom random) {
        return 4 + (int) (random.nextDouble() * 10);
    }

    static int crowd(List<Mob> mobs, double x, double z) {
        int count = 0;
        for (Mob mob : mobs) if (mob.range(x, z) <= CROWD_RANGE) count++;
        return count;
    }

    static Mob nearest(List<Mob> mobs, double x, double z) {
        Mob best = null;
        for (Mob mob : mobs) if (best == null || mob.range(x, z) < best.range(x, z)) best = mob;
        return best;
    }

    /**
     * The mob to aim at. The person nearly always hit the nearest mob, and moved on after about two hits
     * rather than waiting out a flinch: a flinching mob ignores clicks for half a second.
     */
    static Mob chooseTarget(List<Mob> mobs, Integer current, double x, double z, double yaw) {
        Mob best = null;
        double bestScore = Double.MAX_VALUE;
        for (Mob mob : mobs) {
            double range = mob.range(x, z);
            double score = range + Math.abs(wrap(bearing(mob, x, z) - yaw)) * TURN_WEIGHT;
            if (mob.hurtTime() > 0) score += FLINCH_PENALTY;
            if (current != null && mob.id() == current) score -= STICK;
            if (range > SWITCH_REACH) score += range - SWITCH_REACH;
            if (score < bestScore) {
                bestScore = score;
                best = mob;
            }
        }
        return best;
    }

    /** Yaw from a position to a mob, in Minecraft's convention. */
    static double bearing(Mob mob, double x, double z) {
        return Math.toDegrees(Math.atan2(-(mob.x() - x), mob.z() - z));
    }

    static double wrap(double angle) {
        double wrapped = (angle + 180) % 360;
        return (wrapped < 0 ? wrapped + 360 : wrapped) - 180;
    }

    /** Camera pitch for a mob at this range: toward its middle, staying where the recordings kept it. */
    static double pitch(double range) {
        return Math.max(LOW_PITCH, Math.min(HIGH_PITCH, Math.toDegrees(Math.atan2(AIM_DROP, Math.max(.1, range)))));
    }

    /**
     * Closest any mob gets to a player moving at this velocity over the next ticks, checked halfway and at the end,
     * with every mob walking straight at the player.
     */
    static double clearance(List<Mob> mobs, double x, double z, double velocityX, double velocityZ, int ticks, double mobSpeed) {
        double closest = Double.MAX_VALUE;
        for (int t : new int[]{ticks / 2, ticks}) {
            double px = x + velocityX * t, pz = z + velocityZ * t;
            for (Mob mob : mobs) closest = Math.min(closest, mob.range(px, pz) - mobSpeed * t);
        }
        return closest;
    }

    /** Side to strafe to when a run ends, as +1 for left: usually the side with more room, and otherwise the other. */
    static int nextSide(int side, double leftClearance, double rightClearance, ForkableRandom random) {
        int roomier = leftClearance >= rightClearance ? 1 : -1;
        if (Math.abs(leftClearance - rightClearance) > .75) return roomier;
        return random.nextDouble() < .75 ? -side : side;
    }
}
