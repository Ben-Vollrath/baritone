/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.process;

import baritone.behavior.look.ForkableRandom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Geometry and timing of a crop sweep, free of game state. The numbers come from manual
 * wheat-field recordings: the player stays on cleared ground about three blocks from the uncut
 * edge, so the crops in reach form an arc of about 90 degrees, and each camera stroke crosses it.
 */
public final class CropSweepModel {
    /** Crops further apart than this belong to separate fields. */
    private static final int CLUSTER_GAP = 2;
    /** Keeps the crosshair from reaching the ground; 4.5 reach from 1.62 eye height is 21.1 degrees. */
    static final double MAX_SWEEP_PITCH = 20.6;
    /** The camera stays this close to the way the body is going, however the crops lie. */
    static final double MAX_VIEW_OFFSET = 35;
    /** No stroke goes further than this from its center. */
    static final double MAX_STROKE = 62;
    /** Turning fully around costs as much as walking this many blocks further. */
    private static final double TURN_AROUND_BLOCKS = 10;

    private CropSweepModel() {}

    public record Cell(int x, int z) {}
    public record Point(double x, double z) {}
    /** Bearings of the crops in reach, in degrees relative to a reference direction. */
    public record Arc(double low, double high) {
        double middle() { return (low + high) / 2; }
        double width() { return high - low; }
    }
    public record Stroke(int side, double overshoot, int ticks, double pitch) {}

    /** The connected group of crops nearest the player. */
    public static List<Cell> nearestField(Collection<Cell> crops, double x, double z) {
        if (crops.isEmpty()) return List.of();
        Cell nearest = null;
        double best = Double.MAX_VALUE;
        for (Cell cell : crops) {
            double distance = Math.hypot(cell.x + .5 - x, cell.z + .5 - z);
            if (distance < best) {
                best = distance;
                nearest = cell;
            }
        }
        Set<Cell> remaining = new HashSet<>(crops);
        remaining.remove(nearest);
        List<Cell> field = new ArrayList<>(List.of(nearest));
        ArrayDeque<Cell> open = new ArrayDeque<>(field);
        while (!open.isEmpty()) {
            Cell cell = open.poll();
            for (int dx = -CLUSTER_GAP; dx <= CLUSTER_GAP; dx++) {
                for (int dz = -CLUSTER_GAP; dz <= CLUSTER_GAP; dz++) {
                    Cell neighbor = new Cell(cell.x + dx, cell.z + dz);
                    if (remaining.remove(neighbor)) {
                        field.add(neighbor);
                        open.add(neighbor);
                    }
                }
            }
        }
        return field;
    }

    /**
     * Back-and-forth passes over a field, beginning at the corner nearest the player. Passes
     * usually run in the direction the player already faces. The outer ones sit about three
     * blocks inside the field and the rest about eight blocks apart, because the crosshair
     * reaches about four blocks once nearer crops are gone. The less common crosswise choice
     * uses passes about two blocks apart.
     */
    public static List<Point> passes(List<Cell> field, double x, double z, double viewYaw, ForkableRandom random) {
        if (field.isEmpty()) return List.of();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell cell : field) {
            minX = Math.min(minX, cell.x);
            maxX = Math.max(maxX, cell.x + 1);
            minZ = Math.min(minZ, cell.z);
            maxZ = Math.max(maxZ, cell.z + 1);
        }
        double yaw = Math.toRadians(viewYaw);
        boolean facingX = Math.abs(Math.sin(yaw)) >= Math.abs(Math.cos(yaw));
        boolean crosswise = random.nextDouble() < .2;
        boolean alongX = facingX != crosswise;
        double spacingGoal = crosswise ? 2 + random.nextDouble() * .6 : 8 + random.nextDouble() * 1.5;

        double alongMin = alongX ? minX : minZ, alongMax = alongX ? maxX : maxZ;
        double acrossMin = alongX ? minZ : minX, acrossMax = alongX ? maxZ : maxX;
        double playerAlong = alongX ? x : z, playerAcross = alongX ? z : x;
        double extent = acrossMax - acrossMin;
        double edge = Math.min(extent / 2, 2.8 + random.nextDouble() * .6);
        double inner = extent - 2 * edge;
        int count = inner <= 0 ? 1 : (int) Math.round(inner / spacingGoal) + 1;
        double spacing = count > 1 ? inner / (count - 1) : 0;
        boolean ascending = Math.abs(playerAcross - acrossMin) <= Math.abs(playerAcross - acrossMax);
        double inset = Math.min(1.8, (alongMax - alongMin) / 2);
        double low = alongMin + inset, high = alongMax - inset;
        boolean forward = Math.abs(playerAlong - low) <= Math.abs(playerAlong - high);

        List<Point> points = new ArrayList<>(count * 2);
        for (int index = 0; index < count; index++) {
            double across = acrossMin + edge + (count > 1 ? spacing * (ascending ? index : count - 1 - index) : inner / 2);
            double start = forward ? low : high, end = forward ? high : low;
            points.add(alongX ? new Point(start, across) : new Point(across, start));
            points.add(alongX ? new Point(end, across) : new Point(across, end));
            forward = !forward;
        }
        return points;
    }

    /** The span of the given relative bearings, or null when there are none. */
    public static Arc arc(double[] bearings) {
        if (bearings.length == 0) return null;
        double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
        for (double bearing : bearings) {
            low = Math.min(low, bearing);
            high = Math.max(high, bearing);
        }
        return new Arc(low, high);
    }

    /** Where the view centers: on the crops, but never far from the way the body is going. */
    public static double viewCenter(Arc arc) {
        return Math.max(-MAX_VIEW_OFFSET, Math.min(MAX_VIEW_OFFSET, arc.middle()));
    }

    /** Where a stroke to this side ends: just past the last crop, relative to the reference. */
    public static double strokeEnd(Arc arc, Stroke stroke) {
        double center = viewCenter(arc);
        double end = stroke.side > 0 ? arc.high + stroke.overshoot : arc.low - stroke.overshoot;
        return Math.max(center - MAX_STROKE, Math.min(center + MAX_STROKE, end));
    }

    /** A stroke with its details varied a little; its time follows from how far it has to go. */
    public static Stroke stroke(int side, double degrees, ForkableRandom random, double timeScale) {
        double overshoot = 2 + random.nextDouble() * 6;
        int ticks = (int) Math.round(degrees / 6.6 * (.85 + random.nextDouble() * .3) * timeScale);
        double pitch = Math.min(MAX_SWEEP_PITCH, 18.2 + 2.4 * random.nextDouble());
        return new Stroke(side, overshoot, Math.max(4, Math.min(20, ticks)), pitch);
    }

    /** How far off a target is once turning toward it is counted, so straight on is preferred. */
    public static double targetCost(double distance, double turnDegrees) {
        return distance + TURN_AROUND_BLOCKS * Math.abs(turnDegrees) / 180;
    }

    /** Share of a stroke's travel after {@code t} of its time: a quick start that eases out. */
    public static double strokeProgress(double t) {
        double clamped = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - clamped, 4);
    }

    /** How long W stays down or up while strafing left, where the recordings show it tapped. */
    public static int forwardTapTicks(boolean down, ForkableRandom random) {
        double spread = random.nextDouble();
        return down ? 2 + (int) (spread * spread * 11) : 2 + (int) (spread * 4);
    }

    /**
     * Whether a pass point is done with: close to it, or level with it along the line from
     * {@code from}. A sprinting body can circle a point forever without ever getting close.
     */
    public static boolean arrived(double x, double z, Point from, Point to, double radius) {
        if (Math.hypot(to.x - x, to.z - z) < radius) return true;
        double dx = to.x - from.x, dz = to.z - from.z;
        double length = Math.hypot(dx, dz);
        return length > 1e-6 && ((x - from.x) * dx + (z - from.z) * dz) / length >= length - radius;
    }

    /** Distance from a point to a segment, for skipping passes with nothing left along them. */
    public static double distanceToSegment(double x, double z, Point from, Point to) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((x - from.x) * dx + (z - from.z) * dz) / lengthSquared));
        return Math.hypot(x - from.x - t * dx, z - from.z - t * dz);
    }
}
