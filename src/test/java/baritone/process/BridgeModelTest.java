/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.behavior.look.ForkableRandom;
import baritone.process.BridgeModel.TurnKeys;
import org.junit.Test;

import static org.junit.Assert.*;

public class BridgeModelTest {
    @Test public void headingsMatchMinecraftYaw() {
        assertEquals(-90, BridgeModel.heading(1, 0), 1e-9);
        assertEquals(90, BridgeModel.heading(-1, 0), 1e-9);
        assertEquals(0, BridgeModel.heading(0, 1), 1e-9);
        assertEquals(180, Math.abs(BridgeModel.heading(0, -1)), 1e-9);
    }

    @Test public void bridgingViewIsTurnedLeftOfStraightBack() {
        var random = new ForkableRandom(3);
        for (int i = 0; i < 1000; i++) {
            double heading = BridgeModel.heading(1, 0);
            double view = heading + 180 + BridgeModel.bridgeYawOffset(random);
            // Going east, the recordings rested at yaw -225 to -230: back (90) plus about 45, which is 135 to 130 wrapped.
            double wrapped = BridgeModel.wrap(view);
            assertTrue(wrapped >= 130 && wrapped <= 140);
            // The turn there from facing ahead is a left turn of about 135 degrees, the short way round.
            assertTrue(BridgeModel.wrap(wrapped - heading) < 0);
            assertEquals(135, Math.abs(BridgeModel.wrap(wrapped - heading)), 6);
        }
    }

    @Test public void turnKeysGoWThenWdThenDThenSd() {
        var random = new ForkableRandom(4);
        for (int i = 0; i < 1000; i++) {
            double[] points = BridgeModel.turnKeyPoints(random);
            assertTrue(points[0] < points[1] && points[1] < points[2] && points[2] < 1);
            TurnKeys last = TurnKeys.W;
            for (double progress = 0; progress <= 1; progress += .01) {
                TurnKeys keys = BridgeModel.turnKeys(progress, points[0], points[1], points[2]);
                assertTrue(keys.ordinal() >= last.ordinal());
                last = keys;
            }
            assertEquals(TurnKeys.SD, BridgeModel.turnKeys(1, points[0], points[1], points[2]));
        }
    }

    @Test public void samplesStayWithinTheRecordedRanges() {
        var random = new ForkableRandom(5);
        for (int i = 0; i < 2000; i++) {
            double pitch = BridgeModel.bridgePitch(random);
            assertTrue(pitch >= 75.5 && pitch <= 77.8);
            assertTrue(BridgeModel.firstBlockPitch(pitch, random) > pitch);
            assertTrue(BridgeModel.turnPitch(random) >= 84 && BridgeModel.turnPitch(random) <= 89.5);
            double keep = BridgeModel.keepLeft(random);
            assertTrue(keep <= -.12 && keep >= -.25);
            assertTrue(BridgeModel.useFrom(random) < .3); // the sneak edge stops the body 0.3 past the edge
            assertTrue(BridgeModel.turnAt(random) >= .4 && BridgeModel.turnAt(random) <= 2.9);
            int reaction = BridgeModel.reactionTicks(random);
            assertTrue(reaction >= 1 && reaction <= 7);
        }
    }
}
