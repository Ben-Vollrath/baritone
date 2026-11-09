/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.behavior.look.ForkableRandom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decisions behind {@link SandSweep} that need no game. The numbers come from five manual
 * recordings on a 255-cell field with a shovel that breaks sand instantly: the player works
 * around the field's rim and then inward, stays about two blocks from the nearest live sand, and
 * scribbles the crosshair over the patch in front of them.
 */
final class SandSweepModel {
    /** Cells further apart than this belong to separate fields. */
    private static final int CLUSTER_GAP = 2;
    /** How the recordings ordered a field: four of five went around the rim with the sand on the left. */
    private static final double RIM_LEFT_SHARE = .65, RIM_RIGHT_SHARE = .15;
    /** Camera stroke quartiles from the recordings: 32, 58 and 85 degrees. */
    private static final double STROKE_LOW = 32, STROKE_HIGH = 85;
    private static final double DEGREES_PER_TICK = 8, PITCH_DEGREES_PER_TICK = 4;
    private static final double STROKE_SPEED_LOW = 19, STROKE_SPEED_HIGH = 29;
    private static final int CROSS_SAMPLES = 12;
    private static final double REACH = 4.5;
    /** The recordings break most cells about this far from the feet. */
    private static final double PREFERRED_RANGE = 2.2;

    private SandSweepModel() {}

    record Cell(int x, int y, int z) {
        double centerX() { return x + .5; }
        double centerZ() { return z + .5; }
    }

    enum Plan {
        /** Around the rim with the sand on the left, then the same again further in. */
        RIM_LEFT,
        /** Around the rim the other way. */
        RIM_RIGHT,
        /** Back and forth in strips. */
        STRIPS
    }

    /** A live cell the crosshair could go to, as the camera sees it now. */
    record Candidate(double yawOffset, double pitchOffset, double order, int crossed, double range) {}

    /** The keys a tick can hold. */
    record Keys(boolean forward, boolean back, boolean left, boolean right) {
        static final Keys NONE = new Keys(false, false, false, false);
        static final List<Keys> ALL = List.of(new Keys(true, false, false, false), new Keys(true, false, false, true),
                new Keys(false, false, false, true), new Keys(false, true, false, true), new Keys(false, true, false, false),
                new Keys(false, true, true, false), new Keys(false, false, true, false), new Keys(true, false, true, false));

        /** Direction this input moves the body, in degrees right of the view. */
        double angle() {
            return Math.toDegrees(Math.atan2((right ? 1 : 0) - (left ? 1 : 0), (forward ? 1 : 0) - (back ? 1 : 0)));
        }
    }

    static Plan plan(double roll) {
        return roll < RIM_LEFT_SHARE ? Plan.RIM_LEFT : roll < RIM_LEFT_SHARE + RIM_RIGHT_SHARE ? Plan.RIM_RIGHT : Plan.STRIPS;
    }

    /** The connected group of cells nearest the player. */
    static List<Cell> nearestField(Collection<Cell> cells, double x, double z) {
        if (cells.isEmpty()) return List.of();
        Cell nearest = cells.stream().min(Comparator.comparingDouble(cell -> Math.hypot(cell.centerX() - x, cell.centerZ() - z))).orElseThrow();
        Set<Cell> remaining = new HashSet<>(cells);
        remaining.remove(nearest);
        List<Cell> field = new ArrayList<>(List.of(nearest));
        ArrayDeque<Cell> open = new ArrayDeque<>(field);
        while (!open.isEmpty()) {
            Cell cell = open.poll();
            remaining.removeIf(other -> {
                if (Math.abs(other.x - cell.x) > CLUSTER_GAP || Math.abs(other.z - cell.z) > CLUSTER_GAP) return false;
                field.add(other);
                open.add(other);
                return true;
            });
        }
        return field;
    }

    /** Scattered leftovers, nearest first from the player and then from each other. */
    static List<Cell> tour(Collection<Cell> cells, double x, double z) {
        List<Cell> left = new ArrayList<>(cells), tour = new ArrayList<>(cells.size());
        while (!left.isEmpty()) {
            double fromX = x, fromZ = z;
            Cell next = left.stream().min(Comparator.comparingDouble(cell -> Math.hypot(cell.centerX() - fromX, cell.centerZ() - fromZ))).orElseThrow();
            left.remove(next);
            tour.add(next);
            x = next.centerX();
            z = next.centerZ();
        }
        return tour;
    }

    /** How many columns deep each column of a field lies, with 1 touching the outside. */
    static Map<Long, Integer> depths(Collection<Cell> field) {
        Set<Long> columns = new HashSet<>();
        for (Cell cell : field) columns.add(column(cell.x, cell.z));
        Map<Long, Integer> depths = new HashMap<>();
        ArrayDeque<int[]> open = new ArrayDeque<>();
        for (Cell cell : field) {
            long key = column(cell.x, cell.z);
            if (depths.containsKey(key)) continue;
            boolean edge = false;
            for (int dx = -1; dx <= 1 && !edge; dx++) {
                for (int dz = -1; dz <= 1 && !edge; dz++) edge = !columns.contains(column(cell.x + dx, cell.z + dz));
            }
            if (edge) {
                depths.put(key, 1);
                open.add(new int[]{cell.x, cell.z});
            }
        }
        while (!open.isEmpty()) {
            int[] at = open.poll();
            int depth = depths.get(column(at[0], at[1]));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long key = column(at[0] + dx, at[1] + dz);
                    if (columns.contains(key) && !depths.containsKey(key)) {
                        depths.put(key, depth + 1);
                        open.add(new int[]{at[0] + dx, at[1] + dz});
                    }
                }
            }
        }
        return depths;
    }

    static long column(int x, int z) {
        return (long) x << 32 | z & 0xffffffffL;
    }

    /**
     * The next stretch of a field, in the order to clear it. A rim plan returns only the outer
     * band, sorted around the field from the cell nearest the player; what remains is planned
     * again once that band is gone, which also picks up leftovers and new sand.
     */
    static List<Cell> order(List<Cell> field, double x, double z, Plan plan, int band) {
        if (field.isEmpty()) return List.of();
        if (plan == Plan.STRIPS) return strips(field, x, z, band);
        Map<Long, Integer> depths = depths(field);
        List<Cell> outer = field.stream().filter(cell -> depths.get(column(cell.x, cell.z)) <= band).toList();
        double centerX = outer.stream().mapToDouble(Cell::centerX).average().orElseThrow();
        double centerZ = outer.stream().mapToDouble(Cell::centerZ).average().orElseThrow();
        Cell first = outer.stream().min(Comparator.comparingDouble(cell -> Math.hypot(cell.centerX() - x, cell.centerZ() - z))).orElseThrow();
        double start = angle(first, centerX, centerZ);
        // With x east and z south, angle falls going around with the sand on the left.
        int direction = plan == Plan.RIM_LEFT ? -1 : 1;
        Map<Cell, Double> around = new HashMap<>();
        for (Cell cell : outer) {
            double turned = (angle(cell, centerX, centerZ) - start) * direction;
            around.put(cell, (turned % 360 + 360) % 360);
        }
        return outer.stream().sorted(Comparator.comparingDouble(around::get)).toList();
    }

    private static double angle(Cell cell, double centerX, double centerZ) {
        return Math.toDegrees(Math.atan2(cell.centerZ() - centerZ, cell.centerX() - centerX));
    }

    /** The strip nearest the player, along the field's shorter side and from its nearer end. */
    private static List<Cell> strips(List<Cell> field, double x, double z, int band) {
        int minX = field.stream().mapToInt(Cell::x).min().orElseThrow(), maxX = field.stream().mapToInt(Cell::x).max().orElseThrow();
        int minZ = field.stream().mapToInt(Cell::z).min().orElseThrow(), maxZ = field.stream().mapToInt(Cell::z).max().orElseThrow();
        boolean alongZ = maxZ - minZ <= maxX - minX;
        int low = alongZ ? minX : minZ, high = alongZ ? maxX : maxZ;
        double across = alongZ ? x : z, along = alongZ ? z : x;
        boolean fromLow = Math.abs(across - low) <= Math.abs(across - high);
        boolean ascending = Math.abs(along - (alongZ ? minZ : minX)) <= Math.abs(along - (alongZ ? maxZ : maxX));
        return field.stream().filter(cell -> {
            int value = alongZ ? cell.x : cell.z;
            return fromLow ? value < low + band : value > high - band;
        }).sorted(Comparator.comparingInt(cell -> (alongZ ? cell.z : cell.x) * (ascending ? 1 : -1))).toList();
    }

    /**
     * Live columns a straight stroke of the view would pass over on the ground plane at {@code groundY}.
     * With instant breaking every one of them breaks on the way, so this is what a stroke is worth.
     */
    static int crossed(double eyeX, double eyeY, double eyeZ, double fromYaw, double fromPitch, double yawTravel, double pitchTravel,
                       Set<Long> liveColumns, double groundY) {
        Set<Long> seen = new HashSet<>();
        double height = eyeY - groundY;
        for (int step = 1; step <= CROSS_SAMPLES; step++) {
            double t = (double) step / CROSS_SAMPLES;
            double pitch = Math.toRadians(fromPitch + pitchTravel * t), yaw = Math.toRadians(fromYaw + yawTravel * t);
            if (pitch < .05 || height / Math.sin(pitch) > REACH) continue;
            double range = height / Math.tan(pitch);
            long key = column((int) Math.floor(eyeX - Math.sin(yaw) * range), (int) Math.floor(eyeZ + Math.cos(yaw) * range));
            if (liveColumns.contains(key)) seen.add(key);
        }
        return seen.size();
    }

    /**
     * Where the camera stroke goes next: a live cell on the wanted side, for what it crosses, about
     * {@code amplitude} away, about {@code pitchChange} up or down, and early in the order.
     */
    static int strokeEnd(List<Candidate> candidates, int side, double amplitude, double pitchChange) {
        int best = -1;
        double bestCost = Double.MAX_VALUE;
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            double along = candidate.yawOffset * side;
            if (along < 8) continue;
            // Cells per tick, not cells: a long stroke spends most of its time over spent ground.
            double perTick = candidate.crossed / Math.max(3, along / DEGREES_PER_TICK);
            double cost = Math.abs(along - amplitude) * .25 + Math.abs(candidate.pitchOffset - pitchChange) * .4
                    + candidate.order * .4 - perTick * 30 + Math.abs(candidate.range - PREFERRED_RANGE) * 5;
            if (cost < bestCost) {
                bestCost = cost;
                best = index;
            }
        }
        return best;
    }

    /** The closest candidate, for when no stroke to either side is possible. */
    static int nearestToView(List<Candidate> candidates) {
        int best = -1;
        double bestCost = Double.MAX_VALUE;
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            double cost = Math.hypot(candidate.yawOffset, candidate.pitchOffset) + candidate.order * .5;
            if (cost < bestCost) {
                bestCost = cost;
                best = index;
            }
        }
        return best;
    }

    static double strokeAmplitude(ForkableRandom random) {
        return STROKE_LOW + (STROKE_HIGH - STROKE_LOW) * random.nextDouble();
    }

    /** How far along the order a cell is from the cursor, as a cost: the view leads the way the body is going. */
    static double orderFromCursor(int index, int cursor) {
        return index >= cursor ? (index - cursor) * .6 : (cursor - index) * 1.5;
    }

    /** A stroke's top speed in degrees per tick. Peaks in the recordings run about 22, with most of a stroke slower. */
    static double strokeSpeed(ForkableRandom random) {
        return STROKE_SPEED_LOW + (STROKE_SPEED_HIGH - STROKE_SPEED_LOW) * random.nextDouble();
    }

    /** How far a stroke wants to move the pitch, near then far, as the recordings' quartiles of 13 to 29 degrees. */
    static double pitchChange(int direction, ForkableRandom random) {
        return direction * (13 + 16 * random.nextDouble());
    }

    /** The longest a stroke lasts, from how far it goes. The recordings take 6 to 8 ticks over about 60 degrees. */
    static int strokeTicks(double yawDegrees, double pitchDegrees, ForkableRandom random, double timeScale) {
        double ticks = Math.max(Math.abs(yawDegrees) / DEGREES_PER_TICK, Math.abs(pitchDegrees) / PITCH_DEGREES_PER_TICK);
        return (int) Math.max(3, Math.min(22, Math.round(ticks * (.85 + random.nextDouble() * .3) * timeScale)));
    }

    /** Share of a stroke's travel after {@code t} of its time: a quick start that eases out. */
    static double strokeProgress(double t) {
        double clamped = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - clamped, 3);
    }

    /**
     * The keys that best carry the body toward its goal, with the strafe key following the
     * camera's stroke as in the recordings. {@code goalOffset} is the goal's bearing right of the
     * view, {@code urgency} is 0 when the body is where it should be and 1 when it must move.
     * {@code hand} is 1 going around with the sand on the left, where the recordings hold W+D most,
     * and -1 the other way.
     */
    static List<Keys> rankKeys(double goalOffset, double urgency, int strokeSide, boolean forwardAllowed, int hand) {
        List<Keys> ranked = new ArrayList<>(Keys.ALL);
        if (!forwardAllowed) ranked.removeIf(keys -> keys.forward && (keys.left || keys.right));
        Map<Keys, Double> scores = new HashMap<>();
        for (Keys keys : ranked) {
            double score = Math.cos(Math.toRadians(keys.angle() - goalOffset)) * urgency;
            if (keys.right) score += strokeSide > 0 ? .55 : -.35;
            if (keys.left) score += strokeSide < 0 ? .55 : -.35;
            if (keys.forward) score += .3;
            if (keys.forward && keys.right) score += hand * .12;
            if (keys.forward && keys.left) score -= hand * .12;
            if (keys.back) score -= .8;
            scores.put(keys, score);
        }
        ranked.sort(Comparator.comparingDouble((Keys keys) -> scores.get(keys)).reversed());
        return ranked;
    }
}
