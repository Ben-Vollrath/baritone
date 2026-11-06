/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

/** A coherent spatial preference field, fixed for a job and independent of search order. */
public final class RouteVariation {
    private RouteVariation() {}

    public static double value(long seed, double x, double z) {
        double gx = x / 5, gz = z / 5;
        int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
        double tx = fade(gx - ix), tz = fade(gz - iz);
        double a = lerp(hash(seed, ix, iz), hash(seed, ix + 1, iz), tx);
        double b = lerp(hash(seed, ix, iz + 1), hash(seed, ix + 1, iz + 1), tx);
        return lerp(a, b, tz);
    }

    private static double hash(long seed, int x, int z) {
        long n = seed ^ (x * 0x9e3779b97f4a7c15L) ^ (z * 0xc2b2ae3d27d4eb4fL);
        n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L;
        n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL;
        n ^= n >>> 31;
        return (n >>> 11) * 0x1.0p-53;
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
