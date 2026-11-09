/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.behavior.look.ForkableRandom;
import baritone.process.CropSweepModel.Arc;
import baritone.process.CropSweepModel.Cell;
import baritone.process.CropSweepModel.Point;
import baritone.process.CropSweepModel.Stroke;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import static org.junit.Assert.*;

public class CropSweepModelTest {
    private static List<Cell> square(int minX, int minZ, int size) {
        var cells = new ArrayList<Cell>();
        for (int x = minX; x < minX + size; x++) for (int z = minZ; z < minZ + size; z++) cells.add(new Cell(x, z));
        return cells;
    }

    @Test public void separateFieldsAreNotMerged() {
        var crops = new ArrayList<>(square(0, 0, 3));
        crops.addAll(square(20, 0, 3));
        var field = CropSweepModel.nearestField(crops, 19, 1);
        assertEquals(9, field.size());
        assertTrue(field.stream().allMatch(cell -> cell.x() >= 20));
    }

    @Test public void smallGapsStayInOneField() {
        var crops = new ArrayList<>(square(0, 0, 3));
        crops.addAll(square(4, 0, 3));
        crops.remove(new Cell(1, 1));
        assertEquals(17, CropSweepModel.nearestField(crops, 0, 0).size());
    }

    @Test public void passesReachEveryCropAndAlternateDirection() {
        var field = square(8, 4, 15);
        for (long seed = 1; seed <= 200; seed++) {
            var points = CropSweepModel.passes(field, 3.5, 4.5, -90, new ForkableRandom(seed));
            assertEquals(0, points.size() % 2);
            for (var cell : field) {
                double nearest = Double.MAX_VALUE;
                for (int i = 0; i < points.size(); i += 2)
                    nearest = Math.min(nearest, CropSweepModel.distanceToSegment(cell.x() + .5, cell.z() + .5, points.get(i), points.get(i + 1)));
                // Crosshair reach of about 4.3 plus a zigzag of about 1.3 to either side.
                assertTrue("Seed " + seed + " leaves " + cell + " " + nearest + " from every pass", nearest < 5.6);
            }
            for (int i = 2; i < points.size(); i += 2) {
                Point previousStart = points.get(i - 2), previousEnd = points.get(i - 1), start = points.get(i), end = points.get(i + 1);
                double previous = (previousEnd.x() - previousStart.x()) + (previousEnd.z() - previousStart.z());
                double current = (end.x() - start.x()) + (end.z() - start.z());
                assertTrue("Passes should alternate", previous * current < 0);
            }
        }
    }

    @Test public void passesStartNearThePlayerAndMostFollowTheView() {
        var field = square(8, 4, 15);
        int alongView = 0;
        for (long seed = 1; seed <= 400; seed++) {
            var points = CropSweepModel.passes(field, 3.5, 4.5, -90, new ForkableRandom(seed));
            Point start = points.getFirst(), end = points.get(1);
            assertTrue(start.x() < 12 && start.z() < 9);
            if (Math.abs(end.x() - start.x()) > Math.abs(end.z() - start.z())) alongView++;
        }
        assertTrue("Facing passes should dominate: " + alongView, alongView > 280 && alongView < 360);
    }

    @Test public void arcSpansTheCropsAndIsEmptyWithoutThem() {
        assertNull(CropSweepModel.arc(new double[0]));
        var arc = CropSweepModel.arc(new double[]{10, -40, 25});
        assertEquals(-40, arc.low(), 1e-9);
        assertEquals(25, arc.high(), 1e-9);
        assertEquals(65, arc.width(), 1e-9);
        assertEquals(-7.5, arc.middle(), 1e-9);
    }

    @Test public void strokesEndJustPastTheLastCrop() {
        var arc = new Arc(-40, 30);
        assertEquals(35, CropSweepModel.strokeEnd(arc, new Stroke(1, 5, 12, 19)), 1e-9);
        assertEquals(-45, CropSweepModel.strokeEnd(arc, new Stroke(-1, 5, 12, 19)), 1e-9);
    }

    @Test public void strokeEndsFollowTheCropsAsTheyAreCut() {
        var stroke = new Stroke(1, 4, 12, 19);
        double before = CropSweepModel.strokeEnd(new Arc(-30, 40), stroke);
        double after = CropSweepModel.strokeEnd(new Arc(-30, 10), stroke);
        assertTrue("A stroke should not run on past crops already gone", after < before);
    }

    @Test public void viewStaysNearTheWayTheBodyIsGoing() {
        assertEquals(10, CropSweepModel.viewCenter(new Arc(-10, 30)), 1e-9);
        assertEquals(CropSweepModel.MAX_VIEW_OFFSET, CropSweepModel.viewCenter(new Arc(60, 100)), 1e-9);
        assertEquals(-CropSweepModel.MAX_VIEW_OFFSET, CropSweepModel.viewCenter(new Arc(-110, -70)), 1e-9);
    }

    @Test public void strokesNeverLeaveTheirCenterByTooMuch() {
        var stroke = new Stroke(1, 8, 12, 19);
        var wide = new Arc(-110, 110);
        assertEquals(CropSweepModel.MAX_STROKE, CropSweepModel.strokeEnd(wide, stroke), 1e-9);
    }

    @Test public void strokeTimeFollowsItsLength() {
        var random = new ForkableRandom(7);
        double shortTotal = 0, longTotal = 0;
        var durations = new HashSet<Integer>();
        for (int i = 0; i < 2000; i++) {
            var near = CropSweepModel.stroke(1, 30, random, 1);
            var far = CropSweepModel.stroke(-1, 86, random, 1);
            shortTotal += near.ticks();
            longTotal += far.ticks();
            durations.add(far.ticks());
            assertTrue(near.pitch() <= CropSweepModel.MAX_SWEEP_PITCH && near.pitch() >= 18);
            assertTrue(far.overshoot() >= 2 && far.overshoot() <= 8);
        }
        assertEquals("The recorded 86 degree stroke lasts about 13 ticks", 13, longTotal / 2000, 1);
        assertTrue(shortTotal < longTotal);
        assertTrue("Stroke lengths should vary", durations.size() >= 3);
    }

    @Test public void strokeTimeStaysInBounds() {
        var random = new ForkableRandom(3);
        for (int i = 0; i < 200; i++) {
            assertTrue(CropSweepModel.stroke(1, 1, random, .5).ticks() >= 4);
            assertTrue(CropSweepModel.stroke(1, 300, random, 1).ticks() <= 20);
        }
    }

    @Test public void targetsAheadBeatNearerOnesBehind() {
        double ahead = CropSweepModel.targetCost(7, 10);
        double behind = CropSweepModel.targetCost(4, 175);
        assertTrue("Should keep going rather than turn around for a slightly nearer crop", ahead < behind);
        assertTrue("A much nearer crop behind can still be worth turning for", CropSweepModel.targetCost(2, 180) < CropSweepModel.targetCost(14, 0));
        assertEquals(CropSweepModel.targetCost(5, -90), CropSweepModel.targetCost(5, 90), 1e-9);
    }

    @Test public void strokeProgressStartsQuicklyAndEndsExactly() {
        assertEquals(0, CropSweepModel.strokeProgress(0), 1e-9);
        assertEquals(1, CropSweepModel.strokeProgress(1), 1e-9);
        assertEquals(1, CropSweepModel.strokeProgress(2), 1e-9);
        assertTrue(CropSweepModel.strokeProgress(.25) > .4);
        double previous = 0;
        for (double t = .05; t <= 1; t += .05) {
            double progress = CropSweepModel.strokeProgress(t);
            assertTrue(progress > previous);
            previous = progress;
        }
    }

    @Test public void sameSeedGivesTheSamePlan() {
        var field = square(0, 0, 9);
        assertEquals(CropSweepModel.passes(field, -3, 0, -90, new ForkableRandom(11)), CropSweepModel.passes(field, -3, 0, -90, new ForkableRandom(11)));
    }

    @Test public void tinyFieldStillProducesAUsablePass() {
        var points = CropSweepModel.passes(List.of(new Cell(2, 2)), 0, 0, 0, new ForkableRandom(1));
        assertEquals(2, points.size());
        assertTrue(CropSweepModel.distanceToSegment(2.5, 2.5, points.get(0), points.get(1)) < 1);
    }

    @Test public void forwardTapsMatchRecordedHolds() {
        var random = new ForkableRandom(5);
        for (int i = 0; i < 500; i++) {
            int down = CropSweepModel.forwardTapTicks(true, random), up = CropSweepModel.forwardTapTicks(false, random);
            assertTrue(down >= 2 && down <= 13);
            assertTrue(up >= 2 && up <= 6);
        }
    }

    @Test public void passPointsCompleteWhenLevelWithThemEvenIfMissedSideways() {
        var from = new Point(0, 0);
        var to = new Point(10, 0);
        assertFalse(CropSweepModel.arrived(5, 0, from, to, 1.7));
        assertTrue(CropSweepModel.arrived(9, .5, from, to, 1.7));
        assertTrue("A body passing wide of the point has still reached it", CropSweepModel.arrived(9, 6, from, to, 1.7));
        assertTrue(CropSweepModel.arrived(14, 3, from, to, 1.7));
        assertFalse(CropSweepModel.arrived(-3, 0, from, to, 1.7));
        assertTrue(CropSweepModel.arrived(0, 0, from, from, 1.7));
    }
}
