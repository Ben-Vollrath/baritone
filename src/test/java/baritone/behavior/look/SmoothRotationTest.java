/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.behavior.look;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmoothRotationTest {
    @Test public void yawUsesTheShortArcAtBothSeams() {
        assertEquals(2, SmoothRotation.wrapDegrees(-179 - 179), 0);
        assertEquals(-2, SmoothRotation.wrapDegrees(179 - -179), 0);
        assertEquals(-180, SmoothRotation.wrapDegrees(540), 0);
    }

    @Test public void fixedTargetsSettleWithoutOvershootOrExcessSpeed() {
        for (double target : new double[]{-180, -90, -2, -0.01, 0, 0.01, 2, 90, 180}) {
            double position = 0, velocity = 0;
            for (int tick = 0; tick < 100; tick++) {
                double next = SmoothRotation.step(target - position, velocity, 18, 3);
                assertTrue(Math.abs(next) <= 18);
                assertTrue("Acceleration at " + target, Math.abs(next - velocity) <= 3.000001);
                position += next;
                assertTrue("Overshoot at " + target, Math.abs(position) <= Math.abs(target) + 1e-9);
                velocity = next;
            }
            assertEquals(target, position, 1e-6);
        }
    }

    @Test public void previewIsDeterministicAndDoesNotConsumeState() {
        for (int i = 0; i < 100; i++) assertEquals(9, SmoothRotation.step(90, 6, 18, 3), 0);
    }

    @Test public void reversalBrakesBeforeChangingDirection() {
        double speed = SmoothRotation.step(-90, 18, 18, 3);
        assertEquals(15, speed, 0);
        for (int i = 0; i < 20; i++) speed = SmoothRotation.step(-90, speed, 18, 3);
        assertEquals(-18, speed, 1e-9);
    }

    @Test public void stationaryTurnHasLowerPeakSpeedAndAccelerationThanSnap() {
        double position = 0, velocity = 0, peak = 0, acceleration = 0;
        for (int tick = 0; tick < 100; tick++) {
            double next = SmoothRotation.step(90 - position, velocity, 18, 3);
            peak = Math.max(peak, Math.abs(next));
            acceleration = Math.max(acceleration, Math.abs(next - velocity));
            position += next;
            velocity = next;
        }
        assertEquals(90, position, 1e-6);
        assertTrue(peak < 90 / 4.0);
        assertTrue(acceleration <= 3.000001);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsNonFiniteTargets() {
        SmoothRotation.step(Double.NaN, 0, 18, 3);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsInvalidLimits() {
        SmoothRotation.step(90, 0, 18, 0);
    }
}
