/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.behavior.look;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashSet;

public class NaturalAimTest {
    @Test public void previewDoesNotConsumeReactionOrChangeTheChosenTurn() {
        for (long seed=1;seed<40;seed++) {
            var first=NaturalAim.propose(null,90,-90,seed,false,true,true);
            assertEquals(first,NaturalAim.propose(null,90,-90,seed,false,true,true));
            assertEquals(90,first.yaw(),0);
            assertFalse(first.movementReady());
            var state=first.state();
            for (int tick=0;tick<50;tick++) {
                var a=NaturalAim.propose(state,90,-90,seed,false,true,true);
                var b=NaturalAim.propose(state,90,-90,seed,false,true,true);
                assertEquals(a,b);
                assertTrue(Double.isFinite(a.yaw()));
                state=a.state();
            }
            assertFalse(state.turning());
        }
    }

    @Test public void precisionImmediatelyDiscardsNavigationMissAndReaction() {
        var stroke=NaturalAim.propose(null,90,-90,14,false,true,true).state();
        var interaction=NaturalAim.propose(stroke,90,179,14,true,false,true);
        assertEquals(179,interaction.yaw(),0);
        assertTrue(interaction.movementReady());
        assertFalse(interaction.unwrapped());
    }

    @Test public void movingHeadingsHoldExactlyLikeRecordedPlayers() {
        // Recorded players hold the mouse still between flicks; walking adds no drift of its own.
        for (long seed=1;seed<=30;seed++) {
            NaturalAim.State state=null;
            for (int tick=0;tick<100;tick++) {
                var proposal=NaturalAim.propose(state,0,0,seed,false,true,true);
                assertEquals(0,proposal.yaw(),1e-9);
                state=proposal.state();
            }
            assertEquals(0,NaturalAim.propose(state,0,0,seed,false,true,false).yaw(),0);
        }
    }

    @Test public void malformedCalibrationDoesNotReplaceWorkingProfiles() {
        String before=NaturalAim.calibrationId();
        try { NaturalAim.install("{\"schema\":1,\"profiles\":[]}"); fail("Accepted empty calibration"); }
        catch (IllegalArgumentException expected) { assertEquals(before,NaturalAim.calibrationId()); }
    }
}
