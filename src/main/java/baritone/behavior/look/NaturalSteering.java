/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.behavior.look;

/** A damped motor response. Inertia is retained across a target crossing. */
public final class NaturalSteering {
    private NaturalSteering() {}

    public record Parameters(double stiffness, double damping, double speedLimit, double accelerationLimit) {}

    /** One reproducible motor style per job; shared by live steering and its tests. */
    public static Parameters parameters(long seed, double baseSpeed, double baseAcceleration) {
        if (!Double.isFinite(baseSpeed) || !Double.isFinite(baseAcceleration) || !(baseSpeed > 0) || !(baseAcceleration > 0))
            throw new IllegalArgumentException("Invalid motor limits");
        ForkableRandom random = new ForkableRandom(seed);
        double stiffness = .16 + random.nextDouble() * .10;
        double damping = .60 + random.nextDouble() * .25;
        double speed = baseSpeed * (1.4 + random.nextDouble() * 1.4);
        // Enough acceleration to brake a fast hand motion without a prolonged
        // saturated-speed plateau. Mouse quantization still applies afterwards.
        double acceleration = Math.max(baseAcceleration * (6 + random.nextDouble() * 4), speed * .65);
        return new Parameters(stiffness, damping, speed, acceleration);
    }

    public static double step(double error, double velocity, double acceleration,
                              double stiffness, double damping, double speedLimit, double accelerationLimit) {
        if (!Double.isFinite(error) || !Double.isFinite(velocity) || !Double.isFinite(acceleration)
                || !(stiffness > 0) || !(damping > 0) || !(speedLimit > 0) || !(accelerationLimit > 0)) {
            throw new IllegalArgumentException("Invalid motor response");
        }
        double desired = clamp(stiffness * error - damping * velocity, accelerationLimit);
        double nextAcceleration = acceleration + clamp(desired - acceleration, accelerationLimit * 0.5);
        return clamp(velocity + nextAcceleration, speedLimit);
    }

    /** C1 interpolation between game ticks, with unwrapped angles supplied by the caller. */
    public static double interpolate(double from, double to, double fromVelocity, double toVelocity, double partial) {
        double t = Math.max(0, Math.min(1, partial));
        double t2 = t * t, t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * from + (t3 - 2 * t2 + t) * fromVelocity
                + (-2 * t3 + 3 * t2) * to + (t3 - t2) * toVelocity;
    }

    private static double clamp(double value, double limit) { return Math.max(-limit, Math.min(limit, value)); }
}
