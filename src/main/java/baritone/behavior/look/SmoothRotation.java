/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.behavior.look;

/** Pure, tick-based steering. Previewing a target never advances the controller. */
public final class SmoothRotation {
    private SmoothRotation() {}

    public static double wrapDegrees(double angle) {
        return angle - Math.floor((angle + 180.0) / 360.0) * 360.0;
    }

    /** Angles per tick, with a proportional arrival zone and bounded acceleration.
     * A changed target can require a hard stop at the target to avoid aiming past a block.
     */
    public static double step(double error, double velocity, double maxSpeed, double acceleration) {
        if (!Double.isFinite(error) || !Double.isFinite(velocity)
                || !Double.isFinite(maxSpeed) || !Double.isFinite(acceleration)
                || maxSpeed <= 0 || acceleration <= 0) {
            throw new IllegalArgumentException("Steering requires finite angles and positive limits");
        }
        // Reserve enough angular distance to brake on subsequent discrete game ticks.
        double low = 0, high = maxSpeed;
        for (int i = 0; i < 40; i++) {
            double candidate = (low + high) / 2;
            double ticks = Math.ceil(candidate / acceleration);
            double distance = ticks * candidate - acceleration * ticks * (ticks - 1) / 2;
            if (distance <= Math.abs(error)) low = candidate;
            else high = candidate;
        }
        double desired = Math.copySign(Math.min(low, Math.abs(error) * 0.35), error);
        double next = velocity + clamp(desired - velocity, -acceleration, acceleration);
        next = clamp(next, -maxSpeed, maxSpeed);
        if (next * error >= 0 && Math.abs(next) > Math.abs(error)) return error;
        return next;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
