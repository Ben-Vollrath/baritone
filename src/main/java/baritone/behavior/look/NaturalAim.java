/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.behavior.look;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;

/** Perceived navigation headings, distinct from the motor that moves the mouse.
 * Immutable proposals let path prediction explore without consuming live intent.
 */
public final class NaturalAim {
    private NaturalAim() {}
    private record Knot(double seconds, double progress) {}
    private record Profile(int direction, double reaction, double moveAfter, double pitch, List<Knot> knots) {
        double progress(double seconds) {
            if (seconds <= 0) return 0;
            for (int i=1; i<knots.size(); i++) {
                Knot a=knots.get(i-1), b=knots.get(i);
                if (seconds <= b.seconds) {
                    double t=(seconds-a.seconds)/(b.seconds-a.seconds);
                    return a.progress+(b.progress-a.progress)*t;
                }
            }
            return knots.getLast().progress;
        }
    }
    public record Calibration(String id, List<Profile> profiles) {}
    private static volatile Calibration calibration = defaults();
    private record Style(Profile first, Profile second, double blend, double timeScale, double magnitude) {
        double reaction() { return mix(first.reaction,second.reaction,blend)*timeScale; }
        double moveAfter() { return mix(first.moveAfter,second.moveAfter,blend)*timeScale; }
        double progress(double seconds) {
            return mix(first.progress(seconds/timeScale),second.progress(seconds/timeScale),blend)*magnitude;
        }
    }
    public record State(int tick, double origin, double angle, double goal, Style style, boolean turning, double offset) {}
    public record Proposal(State state, double yaw, boolean movementReady, boolean unwrapped) {}

    public static String calibrationId() { return calibration.id; }

    /** Install derived calibration only. The caller owns file location and logging. */
    public static void install(String json) {
        if (json.length()>131072) throw new IllegalArgumentException("Movement calibration is too large");
        var root=JsonParser.parseString(json).getAsJsonObject();
        if (root.get("schema").getAsInt()!=1) throw new IllegalArgumentException("Unknown movement calibration schema");
        var profiles=new ArrayList<Profile>();
        for (var entry:root.getAsJsonArray("profiles")) {
            var p=entry.getAsJsonObject();
            int direction=p.get("direction").getAsInt();
            if (direction!=-1 && direction!=1) throw new IllegalArgumentException("Invalid turn direction");
            double reaction=bounded(p.get("reactionSeconds").getAsDouble(),0,1);
            double moveAfter=bounded(p.get("moveAfterSeconds").getAsDouble(),0,2);
            double pitch=bounded(p.get("walkingPitch").getAsDouble(),-30,45);
            var knots=new ArrayList<Knot>();
            for (var entryKnot:p.getAsJsonArray("knots")) {
                var k=entryKnot.getAsJsonObject();
                double time=bounded(k.get("seconds").getAsDouble(),0,3);
                double progress=bounded(k.get("progress").getAsDouble(),-.5,2.2);
                if (!knots.isEmpty() && time<=knots.getLast().seconds) throw new IllegalArgumentException("Unordered turn knots");
                knots.add(new Knot(time,progress));
            }
            if (knots.size()<4 || knots.size()>200 || knots.getFirst().seconds!=0 || Math.abs(knots.getFirst().progress)>.01
                    || knots.getLast().seconds<.5) throw new IllegalArgumentException("Incomplete turn shape");
            profiles.add(new Profile(direction,reaction,moveAfter,pitch,List.copyOf(knots)));
        }
        if (profiles.size()<2 || profiles.size()>32) throw new IllegalArgumentException("Need between two and 32 movement profiles");
        try {
            String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
            calibration=new Calibration(id,List.copyOf(profiles));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static double bounded(double value,double minimum,double maximum) {
        if (!Double.isFinite(value) || value<minimum || value>maximum) throw new IllegalArgumentException("Invalid movement calibration value");
        return value;
    }

    private static Style style(long seed) {
        var profiles=calibration.profiles;
        var random=new ForkableRandom(seed ^ 0x25a31e46d87b093fL);
        int first=(int)(random.nextDouble()*profiles.size());
        int second=(first+1+(int)(random.nextDouble()*(profiles.size()-1)))%profiles.size();
        return new Style(profiles.get(first),profiles.get(second),.12+.28*random.nextDouble(),
                .90+.20*random.nextDouble(),.97+.06*random.nextDouble());
    }

    public static float walkingPitch(long seed) {
        Style style=style(seed);
        return (float)mix(style.first.pitch,style.second.pitch,style.blend);
    }

    /** Preview one committed mouse tick. Precise interactions never inherit a navigation miss. */
    public static Proposal propose(State previous,double current,double desired,long seed,boolean precise,boolean moving,boolean openSpace) {
        State state=previous;
        if (state==null) {
            Style style=style(seed);
            double angle=SmoothRotation.wrapDegrees(desired-current);
            if (Math.abs(angle)>160) angle=style.first.direction>0 ? (angle+360)%360 : -((-angle+360)%360);
            state=new State(0,current,angle,current+angle,style,!precise && Math.abs(angle)>=100,0);
        }
        double goal=state.goal+SmoothRotation.wrapDegrees(desired-state.goal);
        boolean turning=state.turning && !precise && Math.abs(goal-state.goal)<40;
        double seconds=state.tick*.05;
        double age=seconds-state.style.reaction();
        boolean ready=!turning || age>=state.style.moveAfter();
        double reference=desired;
        if (turning) {
            reference=state.origin;
            if (age>0) reference=goal+state.angle*(state.style.progress(age)-1);
            // Once walking begins, keep the gaze inside the current path's
            // supported lane. Large initial misses happen while the body waits.
            if (ready) {
                reference=goal+Math.max(-2.5,Math.min(2.5,reference-goal));
                turning=false;
            }
        } else if (!precise && !moving && previous!=null && !previous.turning) {
            // Arrival ends the hand stroke where it was aimed; no extra exact
            // centering movement after the body stops.
            reference=goal+state.offset;
        }
        var next=new State(state.tick+1,state.origin,state.angle,goal,state.style,turning,
                turning ? 0 : SmoothRotation.wrapDegrees(reference-goal));
        return new Proposal(next,reference,ready,turning);
    }

    private static double mix(double a,double b,double t) { return a+(b-a)*t; }
    private static Calibration defaults() {
        // General fallback shapes; personal recordings stay in the user's home.
        var overshoot=List.of(new Knot(0,0),new Knot(.12,.12),new Knot(.27,.72),new Knot(.42,1.28),new Knot(.65,1.28),new Knot(.95,.94),new Knot(1.3,1.02));
        var undershoot=List.of(new Knot(0,0),new Knot(.12,.08),new Knot(.30,.64),new Knot(.55,.86),new Knot(.85,.86),new Knot(1.2,1.04));
        return new Calibration("builtin-1",List.of(new Profile(-1,.23,.95,10,overshoot),new Profile(1,.30,.4,8,undershoot)));
    }
}
