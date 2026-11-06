/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.behavior.look;

import org.junit.Test;
import static org.junit.Assert.*;

public class NaturalSteeringTest {
    @Test public void seededResponsesSometimesOvershootAndAlwaysRecover() {
        int overshoots = 0;
        double largest = 0;
        for (long seed = 1; seed <= 200; seed++) {
            ForkableRandom random = new ForkableRandom(seed);
            double k = 0.11 + random.nextDouble() * .04, damping = .50 + random.nextDouble() * .14;
            double speed = 18 * (.89 + random.nextDouble() * .28), limit = 3 * (1.60 + random.nextDouble() * .14);
            double position = 0, velocity = 0, acceleration = 0, peak = 0;
            for (int tick = 0; tick < 180; tick++) {
                double next = NaturalSteering.step(180 - position, velocity, acceleration, k, damping, speed, limit);
                assertTrue(Math.abs(next) <= speed + 1e-9);
                assertTrue(Math.abs(next - velocity) <= limit + 1e-9);
                acceleration = next - velocity;
                velocity = next;
                position += next;
                peak = Math.max(peak, position);
            }
            assertEquals(180, position, 0.001);
            if (peak > 180.2) overshoots++;
            largest = Math.max(largest, peak - 180);
        }
        assertTrue("No visible corrections", overshoots > 10);
        assertTrue("Every turn overshoots", overshoots < 180);
        assertTrue("Excessive correction", largest < 8);
    }

    @Test public void cameraInterpolationHasContinuousPositionAndVelocity() {
        double epsilon = 1e-6;
        assertEquals(179, NaturalSteering.interpolate(179, 183, 3, 4, 0), 0);
        assertEquals(183, NaturalSteering.interpolate(179, 183, 3, 4, 1), 0);
        double outgoing = (NaturalSteering.interpolate(179, 183, 3, 4, 1) - NaturalSteering.interpolate(179, 183, 3, 4, 1-epsilon)) / epsilon;
        double incoming = (NaturalSteering.interpolate(183, 188, 4, 5, epsilon) - NaturalSteering.interpolate(183, 188, 4, 5, 0)) / epsilon;
        assertEquals(outgoing, incoming, 0.0001);
    }

    @Test public void targetChangesDoNotSnapToTheNewTarget() {
        double next = NaturalSteering.step(-20, 8, 0, .13, .56, 18, 5);
        assertEquals(5.5, next, 0);
    }
}
