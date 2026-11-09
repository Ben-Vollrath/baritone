/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.behavior.look.ForkableRandom;
import baritone.process.MobFightModel.Mob;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class MobFightModelTest {
    private static Mob mob(int id, double x, double z, int hurtTime) {
        return new Mob(id, x, z, hurtTime);
    }

    @Test public void clicksKeepTheRecordedRhythm() {
        var random = new ForkableRandom(1);
        double total = 0;
        int count = 20000;
        for (int i = 0; i < count; i++) {
            int gap = MobFightModel.clickGap(random);
            assertTrue(gap >= 2 && gap <= 5);
            total += gap;
        }
        // The recordings clicked about seven times a second.
        double perSecond = 20 / (total / count);
        assertTrue(perSecond > 6.8 && perSecond < 8);
    }

    @Test public void strafeRunsMatchTheRecordedSpread() {
        var random = new ForkableRandom(2);
        var runs = new ArrayList<Integer>();
        for (int i = 0; i < 20000; i++) runs.add(MobFightModel.strafeRun(random));
        runs.sort(null);
        assertEquals(21, runs.get(runs.size() / 2), 2);
        assertEquals(6, runs.get(runs.size() / 10), 3);
        assertEquals(49, runs.get(runs.size() * 9 / 10), 8);
        assertTrue(runs.getFirst() >= 4 && runs.getLast() <= 100);
    }

    @Test public void forwardFollowsRangeAndNeverPushesIntoAMob() {
        assertTrue(MobFightModel.forwardShare(5, 0) > MobFightModel.forwardShare(2.6, 1));
        assertTrue(MobFightModel.forwardShare(2.6, 1) > MobFightModel.forwardShare(2.2, 1));
        assertEquals(0, MobFightModel.forwardShare(1.5, 1), 0);
        assertTrue(MobFightModel.forwardShare(2.6, 4) < MobFightModel.forwardShare(2.6, 1));
    }

    @Test public void theTargetIsTheNearestEvenBehindTheView() {
        var mobs = List.of(mob(1, 0, -2, 0), mob(2, 0, 3.5, 0));
        // Facing +z (yaw 0), with the nearer mob straight behind.
        assertEquals(1, MobFightModel.chooseTarget(mobs, null, 0, 0, 0).id());
    }

    @Test public void aFlinchingMobGivesWayToAComparableOne() {
        var mobs = List.of(mob(1, 0, 2.5, 10), mob(2, 1, 2.6, 0));
        assertEquals(2, MobFightModel.chooseTarget(mobs, 1, 0, 0, 0).id());
    }

    @Test public void theCurrentTargetHoldsAgainstAnEqualOne() {
        var mobs = List.of(mob(1, -1, 2.5, 0), mob(2, 1, 2.5, 0));
        assertEquals(1, MobFightModel.chooseTarget(mobs, 1, 0, 0, 0).id());
        assertEquals(2, MobFightModel.chooseTarget(mobs, 2, 0, 0, 0).id());
    }

    @Test public void pitchStaysInTheRecordedBand() {
        assertEquals(13, MobFightModel.pitch(.5), 0);
        assertEquals(5, MobFightModel.pitch(30), 0);
        // Recorded median, at the median range of a hit.
        assertEquals(10.2, MobFightModel.pitch(2.63), 1.2);
    }

    @Test public void bearingUsesTheGamesYaw() {
        assertEquals(0, MobFightModel.bearing(mob(1, 0, 5, 0), 0, 0), 1e-9);
        assertEquals(-90, MobFightModel.bearing(mob(1, 5, 0, 0), 0, 0), 1e-9);
        assertEquals(90, MobFightModel.bearing(mob(1, -5, 0, 0), 0, 0), 1e-9);
    }

    @Test public void wrapStaysInOneTurn() {
        assertEquals(-170, MobFightModel.wrap(190), 1e-9);
        assertEquals(170, MobFightModel.wrap(-190), 1e-9);
        assertEquals(0, MobFightModel.wrap(720), 1e-9);
    }

    @Test public void clearanceCountsAMobCatchingUp() {
        var mobs = List.of(mob(1, 0, 3, 0));
        // Standing still for 8 ticks with the mob closing at .1 a tick.
        assertEquals(2.2, MobFightModel.clearance(mobs, 0, 0, 0, 0, 8, .1), 1e-9);
        // Walking away holds the distance better than walking toward it.
        assertTrue(MobFightModel.clearance(mobs, 0, 0, 0, -.2, 8, .1) > MobFightModel.clearance(mobs, 0, 0, 0, .2, 8, .1));
    }

    @Test public void aRunEndsTowardTheSideWithMoreRoom() {
        var random = new ForkableRandom(3);
        for (int i = 0; i < 100; i++) {
            assertEquals(1, MobFightModel.nextSide(-1, 3, 1, random));
            assertEquals(-1, MobFightModel.nextSide(1, 1, 3, random));
        }
    }

    @Test public void comparableSidesMostlyAlternate() {
        var random = new ForkableRandom(4);
        int flips = 0, count = 20000;
        for (int i = 0; i < count; i++) if (MobFightModel.nextSide(1, 2, 2.2, random) == -1) flips++;
        assertEquals(.75, flips / (double) count, .02);
    }

    @Test public void theCrowdCountsOnlyCloseMobs() {
        var mobs = List.of(mob(1, 1, 1, 0), mob(2, 3, 0, 0), mob(3, 10, 0, 0));
        assertEquals(2, MobFightModel.crowd(mobs, 0, 0));
        assertEquals(1, MobFightModel.nearest(mobs, 0, 0).id());
    }
}
