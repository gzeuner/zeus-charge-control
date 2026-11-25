package de.zeus.power.wallbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChargerReadingsTest {
    private ChargerReadings reading(int phases, int mode, int status) {
        var phase = new ChargerReadings.Phase(230, 0, 0);
        return new ChargerReadings(status, phases, 3, 16, 0, phase, phase, phase,
                0, 0, mode, 16);
    }

    @Test
    void phaseZeroMeansThreePhasesAndAllRegisterValuesDecode() {
        assertEquals("3 (L1-L3)", reading(0, 0, 0).phaseDescription());
        assertEquals("1 (L1)", reading(1, 0, 4).phaseDescription());
        assertEquals("1 (L2)", reading(2, 0, 4).phaseDescription());
        assertEquals("1 (L3)", reading(3, 0, 4).phaseDescription());
        assertEquals("Unbekannt", reading(4, 0, 4).phaseDescription());
        assertEquals("2", reading(5, 0, 4).phaseDescription());
        assertEquals("2", reading(6, 0, 4).phaseDescription());
        assertEquals("2", reading(7, 0, 4).phaseDescription());
    }

    @Test
    void ir53IsDecodedAsFastSmartOrUnknownWithoutTreatingZeroAsMissing() {
        assertEquals("Fast", reading(0, 1, 4).modeDescription());
        assertEquals("Smart", reading(0, 2, 4).modeDescription());
        assertEquals("Keine Session / unbekannt", reading(0, 0, 0).modeDescription());
        assertEquals("Keine Session / unbekannt", reading(0, 3, 4).modeDescription());
    }

    @Test
    void phaseFallbackUsesHardwareCountThenConfigAndIr1IsRawOnly() {
        assertEquals(3, reading(0, 0, 0).effectivePhases(1));
        assertEquals(3, reading(5, 0, 4).effectivePhases(1));
        assertEquals("IR1023", reading(5, 0, 4).phaseSource());

        var activeThreePhase = new ChargerReadings(4, 5, 2, 16, 0,
                new ChargerReadings.Phase(230, 15.9f, 0),
                new ChargerReadings.Phase(230, 15.9f, 0),
                new ChargerReadings.Phase(230, 15.9f, 0),
                11.07f, 0, 1, 16);
        assertEquals(3, activeThreePhase.measuredActivePhases());
        assertEquals(2, new PhaseTracker().update(activeThreePhase, 2).phases(),
                "one current sample must not override the IR 1023 fallback");
        var stableThreePhase = new PhaseTracker();
        stableThreePhase.update(activeThreePhase, 2);
        stableThreePhase.update(activeThreePhase, 2);
        assertEquals(3, stableThreePhase.update(activeThreePhase, 2).phases());

        var unknownIr1 = reading(4, 0, 4);
        assertEquals(3, unknownIr1.effectivePhases(1));
        assertEquals("IR1023", unknownIr1.phaseSource());

        var noReportedPhaseCount = new ChargerReadings(0, 5, 0, 0, 0,
                new ChargerReadings.Phase(230, 0, 0), new ChargerReadings.Phase(230, 0, 0),
                new ChargerReadings.Phase(230, 0, 0), 0, 0, 0, 0);
        assertEquals(1, noReportedPhaseCount.effectivePhases(1));
        assertEquals("wallbox.phases", noReportedPhaseCount.phaseSource());
    }

    @Test
    void phaseTrackerNeedsThreePollsAndIgnoresShortDropouts() {
        var tracker = new PhaseTracker();
        ChargerReadings two = withCurrents(2, 2, 0);
        ChargerReadings three = withCurrents(2, 2, 2);
        assertEquals(3, tracker.update(two, 3).phases(), "IR 1023 is used while measurements settle");
        assertEquals(3, tracker.update(two, 3).phases());
        assertEquals(2, tracker.update(two, 3).phases());
        assertEquals(2, tracker.update(three, 3).phases(), "a single three-phase sample cannot upgrade");
        assertEquals(2, tracker.update(two, 3).phases(), "one two-phase outlier cannot downgrade");
        assertEquals(2, tracker.update(three, 3).phases());
        assertEquals(2, tracker.update(three, 3).phases());
        assertEquals(3, tracker.update(three, 3).phases(), "three stable polls upgrade the count");
        assertEquals(3, tracker.update(two, 3).phases(), "a short phase loss cannot change ECO's ceiling");
        for (int i = 0; i < 3; i++) assertEquals(3, tracker.update(two, 3).phases());
        assertEquals(2, tracker.update(two, 3).phases(), "five stable lower polls downgrade the count");
    }

    @Test
    void currentsBelowTheSumThresholdDoNotBecomeStable() {
        var tracker = new PhaseTracker();
        ChargerReadings one = withCurrents(2, 0, 0);
        for (int i = 0; i < 5; i++) assertEquals(3, tracker.update(one, 3).phases());
    }

    private ChargerReadings withCurrents(float l1, float l2, float l3) {
        return new ChargerReadings(4, 6, 3, 16, 0,
                new ChargerReadings.Phase(230, l1, 0), new ChargerReadings.Phase(230, l2, 0),
                new ChargerReadings.Phase(230, l3, 0), 0, 0, 1, 16);
    }
}
