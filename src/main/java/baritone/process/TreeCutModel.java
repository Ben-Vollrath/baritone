/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.behavior.look.ForkableRandom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Decisions behind {@link TreeCutter} that need no game: which tree next, and in what order to cut it. */
final class TreeCutModel {
    private TreeCutModel() {}

    /** A choice among comparable trees: this much extra distance can still lose to a nearer one. */
    private static final double TREE_CHOICE_NOISE = 4.5;

    record Log(int x, int y, int z) {
        boolean touches(Log other) {
            return Math.abs(x - other.x) <= 1 && Math.abs(y - other.y) <= 1 && Math.abs(z - other.z) <= 1;
        }
    }

    /** Cut orders seen across 30 recorded tree visits, with their shares. Height 0 is the log at the feet. */
    enum Pattern {
        /** 1, 0, then up from under the trunk. */
        EYE_BOTTOM_UP(.40),
        /** 1, then up while pressed against the trunk, 0 on the way out. */
        EYE_UP_BOTTOM_LAST(.27),
        /** 2 and up, then 1 and 0. */
        ABOVE_UP_LOW_LAST(.20),
        /** 0, 1, then up. */
        BOTTOM_UP(.10),
        /** 2, 1, 0, then the rest upward. */
        ABOVE_DOWN_UP(.03);

        final double share;

        Pattern(double share) {
            this.share = share;
        }
    }

    static Pattern pattern(double roll) {
        double seen = 0;
        for (Pattern pattern : Pattern.values()) {
            seen += pattern.share;
            if (roll < seen) return pattern;
        }
        return Pattern.EYE_BOTTOM_UP;
    }

    /** Connected groups of logs, so a branch or a wide trunk counts as one tree. */
    static List<List<Log>> trees(Collection<Log> logs) {
        List<List<Log>> trees = new ArrayList<>();
        Set<Log> left = new HashSet<>(logs);
        for (Log start : logs) {
            if (!left.remove(start)) continue;
            List<Log> tree = new ArrayList<>(List.of(start));
            ArrayDeque<Log> open = new ArrayDeque<>(tree);
            while (!open.isEmpty()) {
                Log current = open.poll();
                for (var iterator = left.iterator(); iterator.hasNext(); ) {
                    Log other = iterator.next();
                    if (current.touches(other)) {
                        iterator.remove();
                        tree.add(other);
                        open.add(other);
                    }
                }
            }
            trees.add(tree);
        }
        return trees;
    }

    /** The nearest tree give or take a little, so that repeated runs don't take one fixed route. */
    static List<Log> nextTree(List<List<Log>> trees, double x, double z, ForkableRandom random) {
        List<Log> best = null;
        double bestCost = Double.MAX_VALUE;
        for (List<Log> tree : trees) {
            double cost = distance(tree, x, z) + random.nextDouble() * TREE_CHOICE_NOISE;
            if (cost < bestCost) {
                bestCost = cost;
                best = tree;
            }
        }
        return best;
    }

    /** Horizontal distance to the middle of the tree's nearest column. */
    static double distance(List<Log> tree, double x, double z) {
        double best = Double.MAX_VALUE;
        for (Log log : tree) best = Math.min(best, Math.hypot(log.x + .5 - x, log.z + .5 - z));
        return best;
    }

    static List<Log> cutOrder(List<Log> tree, Pattern pattern) {
        int floor = tree.stream().mapToInt(Log::y).min().orElse(0);
        List<Log> ascending = new ArrayList<>(tree);
        ascending.sort(Comparator.comparingInt(Log::y));
        List<Log> bottom = ascending.stream().filter(log -> log.y == floor).toList();
        List<Log> eye = ascending.stream().filter(log -> log.y == floor + 1).toList();
        List<Log> above = ascending.stream().filter(log -> log.y >= floor + 2).toList();
        List<Log> order = new ArrayList<>(tree.size());
        switch (pattern) {
            case EYE_BOTTOM_UP -> {
                order.addAll(eye);
                order.addAll(bottom);
                order.addAll(above);
            }
            case EYE_UP_BOTTOM_LAST -> {
                order.addAll(eye);
                order.addAll(above);
                order.addAll(bottom);
            }
            case ABOVE_UP_LOW_LAST -> {
                order.addAll(above);
                order.addAll(eye);
                order.addAll(bottom);
            }
            case BOTTOM_UP -> order.addAll(ascending);
            case ABOVE_DOWN_UP -> {
                if (!above.isEmpty()) order.add(above.getFirst());
                order.addAll(eye);
                order.addAll(bottom);
                order.addAll(above.stream().skip(1).toList());
            }
        }
        return order;
    }
}
