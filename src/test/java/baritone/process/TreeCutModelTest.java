/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.behavior.look.ForkableRandom;
import baritone.process.TreeCutModel.Log;
import baritone.process.TreeCutModel.Pattern;
import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class TreeCutModelTest {
    private static List<Log> trunk(int x, int z, int height) {
        var logs = new ArrayList<Log>();
        for (int y = 200; y < 200 + height; y++) logs.add(new Log(x, y, z));
        return logs;
    }

    private static List<Integer> heights(List<Log> order) {
        return order.stream().map(log -> log.y() - 200).toList();
    }

    @Test public void cutOrdersMatchTheRecordedOnes() {
        assertEquals(List.of(1, 0, 2, 3, 4), heights(TreeCutModel.cutOrder(trunk(0, 0, 5), Pattern.EYE_BOTTOM_UP)));
        assertEquals(List.of(1, 2, 3, 4, 0), heights(TreeCutModel.cutOrder(trunk(0, 0, 5), Pattern.EYE_UP_BOTTOM_LAST)));
        assertEquals(List.of(2, 3, 4, 1, 0), heights(TreeCutModel.cutOrder(trunk(0, 0, 5), Pattern.ABOVE_UP_LOW_LAST)));
        assertEquals(List.of(0, 1, 2, 3, 4), heights(TreeCutModel.cutOrder(trunk(0, 0, 5), Pattern.BOTTOM_UP)));
        assertEquals(List.of(2, 1, 0, 3, 4), heights(TreeCutModel.cutOrder(trunk(0, 0, 5), Pattern.ABOVE_DOWN_UP)));
    }

    @Test public void everyPatternCutsEveryLogOnceEvenOnPartlyCutTrees() {
        var partial = new ArrayList<>(trunk(0, 0, 6));
        partial.remove(1);
        for (Pattern pattern : Pattern.values()) {
            for (List<Log> tree : List.of(trunk(0, 0, 6), trunk(0, 0, 1), trunk(0, 0, 2), partial)) {
                var order = TreeCutModel.cutOrder(tree, pattern);
                assertEquals(tree.size(), order.size());
                assertEquals(new HashSet<>(tree), new HashSet<>(order));
            }
        }
    }

    @Test public void patternSharesAddUp() {
        assertEquals(1, java.util.Arrays.stream(Pattern.values()).mapToDouble(p -> p.share).sum(), 1e-9);
        var counts = new EnumMap<Pattern, Integer>(Pattern.class);
        var random = new ForkableRandom(5);
        for (int i = 0; i < 20000; i++) counts.merge(TreeCutModel.pattern(random.nextDouble()), 1, Integer::sum);
        for (Pattern pattern : Pattern.values()) assertEquals(pattern.share, counts.get(pattern) / 20000.0, .02);
    }

    @Test public void connectedLogsFormOneTreeAndSeparateOnesDoNot() {
        var logs = new ArrayList<>(trunk(0, 0, 5));
        logs.add(new Log(1, 204, 1));
        logs.addAll(trunk(6, 0, 4));
        var trees = TreeCutModel.trees(logs);
        assertEquals(2, trees.size());
        assertEquals(Set.of(6, 4), Set.of(trees.get(0).size(), trees.get(1).size()));
    }

    @Test public void treeChoiceFavoursNearOnesButVaries() {
        var near = trunk(3, 0, 4);
        var comparable = trunk(0, 4, 4);
        var far = trunk(60, 0, 4);
        var picked = new java.util.HashMap<Integer, Integer>();
        var random = new ForkableRandom(11);
        for (int i = 0; i < 2000; i++) {
            var tree = TreeCutModel.nextTree(List.of(near, comparable, far), .5, .5, random);
            picked.merge(tree.getFirst().x(), 1, Integer::sum);
        }
        assertFalse("A far tree is never next while near ones remain", picked.containsKey(60));
        assertTrue(picked.get(3) > 400 && picked.get(0) > 400);
    }

    @Test public void noTreesMeansNoChoice() {
        assertNull(TreeCutModel.nextTree(List.of(), 0, 0, new ForkableRandom(1)));
        assertTrue(TreeCutModel.trees(List.of()).isEmpty());
    }
}
