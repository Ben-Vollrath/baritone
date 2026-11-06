/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class RouteVariationTest {
    @Test public void routesHaveStableNonnegativeCoherentPreferences() {
        for (long seed = 0; seed < 30; seed++) for (int x = -10; x < 10; x++) {
            double a = RouteVariation.value(seed, x, 3);
            assertTrue(a >= 0 && a <= 1);
            assertEquals(a, RouteVariation.value(seed, x, 3), 0);
            assertTrue(Math.abs(a - RouteVariation.value(seed, x + .01, 3)) < .01);
        }
        assertNotEquals(RouteVariation.value(1, 7, 3), RouteVariation.value(2, 7, 3), .0001);
    }
}
