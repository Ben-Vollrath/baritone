/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.behavior.look.ForkableRandom;

/**
 * Choices for {@link BridgeProcess}, taken from five recorded bridges over a 12-block gap. The person
 * sprints to the edge, starts sneaking about a block before it, turns left about 135 degrees with the
 * keys going W, W+D, D, S+D, and then backs across on S+D with right-click held, looking about 45
 * degrees off straight back and 77 degrees down. Vanilla repeats the held click every 4 ticks; every
 * third click lands on the edge block's side, so each block takes 12 ticks. On the landing they let go of
 * right-click at its edge, of sneak a little later, back up a few ticks, turn to look back along the
 * bridge with the view level, and stop.
 *
 * Distances are along the direction of travel, in blocks. The edge is the face between the last solid
 * floor cell and the gap.
 */
final class BridgeModel {
    private BridgeModel() {}

    /**
     * Sneak goes down with the body centre this far before the edge. The recordings show the crouch from 0.67 to 1.16
     * before it, and the crouch shows about two sprinting ticks after the key, so the key leads by half a block.
     */
    static double sneakBefore(ForkableRandom random) {
        return 1.5 + random.nextDouble() * .45;
    }

    /** Ticks from the start before the first key (recorded 2 to 9, median 5; the key shows a tick later). */
    static int reactionTicks(ForkableRandom random) {
        return 1 + (int) (random.nextDouble() * 7);
    }

    /** How far off straight back the view rests while bridging, turned left (recorded 41 to 46). */
    static double bridgeYawOffset(ForkableRandom random) {
        return 41 + random.nextDouble() * 5;
    }

    /** Where the body keeps across the line while bridging: right of its middle, facing the way of travel (recorded 0.07 to 0.24). */
    static double keepLeft(ForkableRandom random) {
        return -.12 - random.nextDouble() * .13;
    }

    /** Pitch while bridging (recorded medians 75.6 to 77.7). */
    static double bridgePitch(ForkableRandom random) {
        return 75.5 + random.nextDouble() * 2.3;
    }

    /** Pitch the turn swings through before settling (recorded peaks 84.9 to 90). */
    static double turnPitch(ForkableRandom random) {
        return 84 + random.nextDouble() * 5.5;
    }

    /** Pitch on the first block, easing down to the bridging pitch over the next two (recorded 76.8 to 80.2). */
    static double firstBlockPitch(double bridgePitch, ForkableRandom random) {
        return bridgePitch + 1.5 + random.nextDouble() * 3;
    }

    /** Ticks a view rests before its next small drift while bridging. About 70% of ticks the view does not move. */
    static int driftRest(ForkableRandom random) {
        return 12 + (int) (random.nextDouble() * 30);
    }

    /** A small change of the resting view: yaw within this many degrees of its middle (recorded spread about 3). */
    static double driftYaw(ForkableRandom random) {
        return (random.nextDouble() * 2 - 1) * 4;
    }

    static double driftPitch(ForkableRandom random) {
        return (random.nextDouble() * 2 - 1) * .8;
    }

    /** Overhang past the edge block at which right-click goes down for the first block (recorded 0.2 to 0.3). */
    static double useFrom(ForkableRandom random) {
        return .17 + random.nextDouble() * .1;
    }

    /** Body past the landing's edge when right-click comes up (recorded -0.07 to 0.03). */
    static double useReleaseAt(ForkableRandom random) {
        return -.08 + random.nextDouble() * .11;
    }

    /** Body past the landing's edge when sneak comes up: recorded 0.17 to 0.27 by the crouch, which ends about two ticks after the key. */
    static double sneakReleaseAt(ForkableRandom random) {
        return -.12 + random.nextDouble() * .12;
    }

    /** Body past the landing's edge where the turn to look back starts, backing up on S+D until then (recorded 0.36 to 2.95). */
    static double turnAt(ForkableRandom random) {
        return .4 + random.nextDouble() * 2.5;
    }

    /** Ticks S+D stays down into that turn (recorded 0 to 4). */
    static int turnSdTicks(ForkableRandom random) {
        return (int) (random.nextDouble() * 5);
    }

    /** Then ticks of S alone, then nothing (recorded 0 to 6). */
    static int turnBackTicks(ForkableRandom random) {
        return (int) (random.nextDouble() * 7);
    }

    /** Pitch when looking back along the finished bridge (recorded 1.5 to 12.9). */
    static double endPitch(ForkableRandom random) {
        return 1.5 + random.nextDouble() * 11;
    }

    /** Yaw off straight back when looking back along the bridge (recorded -2 to 7). */
    static double endYawOffset(ForkableRandom random) {
        return -2 + random.nextDouble() * 9;
    }

    /** Keys during the turn, by how far round it is (0 facing ahead, 1 at the bridging view). */
    enum TurnKeys { W, WD, D, SD }

    static TurnKeys turnKeys(double progress, double wdFrom, double dFrom, double sdFrom) {
        if (progress < wdFrom) return TurnKeys.W;
        if (progress < dFrom) return TurnKeys.WD;
        if (progress < sdFrom) return TurnKeys.D;
        return TurnKeys.SD;
    }

    /** Where in the turn W+D, D and S+D start (recorded W+D at 0.05 to 0.3, D at 0.25 to 0.6, S+D at 0.6 to 0.95). */
    static double[] turnKeyPoints(ForkableRandom random) {
        double wd = .05 + random.nextDouble() * .2;
        double d = Math.max(wd + .05, .25 + random.nextDouble() * .3);
        double sd = Math.max(d + .1, .6 + random.nextDouble() * .3);
        return new double[]{wd, d, sd};
    }

    /** Yaw for walking in direction (dx, dz). */
    static double heading(int dx, int dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    static double wrap(double angle) {
        angle %= 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }
}
