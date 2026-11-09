/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.behavior.look.ForkableRandom;
import baritone.process.SandSweepModel.Candidate;
import baritone.process.SandSweepModel.Cell;
import baritone.process.SandSweepModel.Keys;
import baritone.process.SandSweepModel.Plan;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class SandSweepModelTest {
    private static List<Cell> rectangle(int minX, int minZ, int width, int depth) {
        List<Cell> cells = new ArrayList<>();
        for (int x = minX; x < minX + width; x++) for (int z = minZ; z < minZ + depth; z++) cells.add(new Cell(x, 199, z));
        return cells;
    }

    @Test public void plansCoverEveryShare() {
        assertEquals(Plan.RIM_LEFT, SandSweepModel.plan(0));
        assertEquals(Plan.RIM_RIGHT, SandSweepModel.plan(.7));
        assertEquals(Plan.STRIPS, SandSweepModel.plan(.99));
    }

    @Test public void separateFieldsAreNotMerged() {
        List<Cell> cells = new ArrayList<>(rectangle(0, 0, 3, 3));
        cells.addAll(rectangle(20, 0, 3, 3));
        List<Cell> field = SandSweepModel.nearestField(cells, 19, 1);
        assertEquals(9, field.size());
        assertTrue(field.stream().allMatch(cell -> cell.x() >= 20));
    }

    @Test public void depthCountsInFromTheOutside() {
        var depths = SandSweepModel.depths(rectangle(0, 0, 7, 7));
        assertEquals(1, (int) depths.get(0L << 32 | 0));
        assertEquals(4, (int) depths.get(3L << 32 | 3));
    }

    @Test public void rimPlanTakesOnlyTheOuterBandAndStartsBesideThePlayer() {
        List<Cell> field = rectangle(8, 4, 17, 15);
        List<Cell> order = SandSweepModel.order(field, 3.5, 4.5, Plan.RIM_LEFT, 4);
        assertEquals(field.size() - 9 * 7, order.size());
        Cell first = order.getFirst();
        assertTrue("first " + first, first.x() <= 10 && first.z() <= 6);
    }

    @Test public void rimLeftGoesDownTheWestSideFirst() {
        List<Cell> order = SandSweepModel.order(rectangle(8, 4, 17, 15), 3.5, 4.5, Plan.RIM_LEFT, 4);
        Cell quarter = order.get(order.size() / 4);
        assertTrue("quarter " + quarter, quarter.z() > 11 && quarter.x() < 16);
        List<Cell> other = SandSweepModel.order(rectangle(8, 4, 17, 15), 3.5, 4.5, Plan.RIM_RIGHT, 4);
        Cell otherQuarter = other.get(other.size() / 4);
        assertTrue("other " + otherQuarter, otherQuarter.z() < 11);
    }

    @Test public void repeatedPlanningClearsAnyField() {
        for (Plan plan : Plan.values()) {
            Set<Cell> left = new HashSet<>(rectangle(0, 0, 17, 15));
            int rounds = 0;
            while (!left.isEmpty()) {
                List<Cell> order = SandSweepModel.order(new ArrayList<>(left), 0, 0, plan, 4);
                assertFalse(order.isEmpty());
                order.forEach(left::remove);
                assertTrue(++rounds < 20);
            }
        }
    }

    @Test public void stripsStartAtTheNearSideAndEnd() {
        List<Cell> order = SandSweepModel.order(rectangle(8, 4, 17, 15), 3.5, 4.5, Plan.STRIPS, 4);
        assertEquals(4 * 15, order.size());
        assertTrue(order.stream().allMatch(cell -> cell.x() < 12));
        assertEquals(4, order.getFirst().z());
    }

    @Test public void strokeEndIsOnTheWantedSideAndFavoursEarlyCells() {
        List<Candidate> candidates = List.of(new Candidate(-50, 0, 0, 0, 2.4), new Candidate(60, 0, 1, 0, 2.4), new Candidate(55, 0, 30, 0, 2.4), new Candidate(5, 0, 2, 0, 2.4));
        assertEquals(1, SandSweepModel.strokeEnd(candidates, 1, 58, 0));
        assertEquals(0, SandSweepModel.strokeEnd(candidates, -1, 58, 0));
        assertEquals(-1, SandSweepModel.strokeEnd(List.of(new Candidate(5, 0, 0, 0, 2.4)), 1, 58, 0));
        assertEquals(0, SandSweepModel.nearestToView(List.of(new Candidate(5, 0, 0, 0, 2.4))));
    }

    @Test public void strokeEndPrefersCrossingMoreSand() {
        List<Candidate> candidates = List.of(new Candidate(58, 0, 0, 1, 2.4), new Candidate(30, 0, 5, 5, 2.4));
        assertEquals(1, SandSweepModel.strokeEnd(candidates, 1, 58, 0));
    }

    @Test public void strokeEndFollowsTheWantedPitchChange() {
        List<Candidate> candidates = List.of(new Candidate(50, -20, 0, 0, 2.4), new Candidate(50, 20, 0, 0, 2.4));
        assertEquals(0, SandSweepModel.strokeEnd(candidates, 1, 50, -20));
        assertEquals(1, SandSweepModel.strokeEnd(candidates, 1, 50, 20));
    }

    @Test public void crossedCountsLiveColumnsUnderTheStroke() {
        Set<Long> live = new HashSet<>();
        // Looking south at 35 degrees from 1.62 above the ground lands about 2.3 blocks out.
        live.add(SandSweepModel.column(0, 2));
        assertEquals(1, SandSweepModel.crossed(.5, 201.62, .5, 0, 35, 0, 0, live, 200));
        // Too far down the sky: nothing in reach, however much sand lies under the line.
        assertEquals(0, SandSweepModel.crossed(.5, 201.62, .5, 0, 5, 0, 0, Set.of(SandSweepModel.column(0, 18)), 200));
        // A stroke across a row counts each column once.
        for (int x = -3; x <= 3; x++) live.add(SandSweepModel.column(x, 2));
        assertTrue(SandSweepModel.crossed(.5, 201.62, .5, -45, 35, 90, 0, live, 200) >= 3);
    }

    @Test public void strokeEndPrefersACellAtTheUsualRange() {
        List<Candidate> candidates = List.of(new Candidate(50, 0, 0, 0, 4.1), new Candidate(50, 0, 0, 0, 2.4));
        assertEquals(1, SandSweepModel.strokeEnd(candidates, 1, 50, 0));
    }

    @Test public void cellsAheadOfTheCursorCostLessThanCellsBehindIt() {
        assertEquals(0, SandSweepModel.orderFromCursor(10, 10), 0);
        assertTrue(SandSweepModel.orderFromCursor(15, 10) < SandSweepModel.orderFromCursor(5, 10));
    }

    @Test public void strokeEndCountsCellsPerTick() {
        // 60 degrees over 5 cells or 24 degrees over 3: the short one takes more per tick.
        List<Candidate> candidates = List.of(new Candidate(60, 0, 0, 5, 2.4), new Candidate(24, 0, 0, 3, 2.4));
        assertEquals(1, SandSweepModel.strokeEnd(candidates, 1, 40, 0));
    }

    @Test public void tourGoesNearestFirst() {
        List<Cell> tour = SandSweepModel.tour(List.of(new Cell(20, 199, 0), new Cell(1, 199, 0), new Cell(10, 199, 0)), 0, 0);
        assertEquals(List.of(new Cell(1, 199, 0), new Cell(10, 199, 0), new Cell(20, 199, 0)), tour);
    }

    @Test public void strokeTimeFollowsDistanceWithinLimits() {
        ForkableRandom random = new ForkableRandom(5);
        for (int i = 0; i < 200; i++) {
            int ticks = SandSweepModel.strokeTicks(60, 10, random, 1);
            assertTrue("ticks " + ticks, ticks >= 6 && ticks <= 9);
            assertEquals(3, SandSweepModel.strokeTicks(1, 0, random, 1));
            assertTrue(SandSweepModel.strokeTicks(1000, 0, random, 1) <= 22);
        }
    }

    @Test public void keysFollowTheStrokeWhenThereIsNoHurry() {
        assertEquals(new Keys(true, false, false, true), SandSweepModel.rankKeys(0, 0, 1, true, 1).getFirst());
        assertEquals(new Keys(true, false, true, false), SandSweepModel.rankKeys(0, 0, -1, true, 1).getFirst());
        assertEquals(new Keys(false, false, true, false), SandSweepModel.rankKeys(0, 0, -1, false, 1).getFirst());
    }

    @Test public void keysGoForTheGoalWhenItMatters() {
        Keys behind = SandSweepModel.rankKeys(180, 1, 1, true, 1).getFirst();
        assertFalse(behind.forward());
        Keys ahead = SandSweepModel.rankKeys(0, 1, -1, true, 1).getFirst();
        assertTrue(ahead.forward());
    }
}
