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
    }

    @Test
    void ir53IsDecodedAsFastSmartOrUnknownWithoutTreatingZeroAsMissing() {
        assertEquals("Fast", reading(0, 1, 4).modeDescription());
        assertEquals("Smart", reading(0, 2, 4).modeDescription());
        assertEquals("Keine Session / unbekannt", reading(0, 0, 0).modeDescription());
        assertEquals("Keine Session / unbekannt", reading(0, 3, 4).modeDescription());
    }

    @Test
    void phaseSelectionPrefersMeasuredCurrentsThenIr1ThenHardwareCount() {
        assertEquals(3, reading(0, 0, 0).effectivePhases(1));
        assertEquals(2, reading(5, 0, 4).effectivePhases(3));

        var activeThreePhase = new ChargerReadings(4, 5, 3, 16, 0,
                new ChargerReadings.Phase(230, 15.9f, 0),
                new ChargerReadings.Phase(230, 15.9f, 0),
                new ChargerReadings.Phase(230, 15.9f, 0),
                11.07f, 0, 1, 16);
        assertEquals(3, activeThreePhase.measuredActivePhases());
        assertEquals(3, activeThreePhase.effectivePhases(2));
        assertEquals("measured-phase-currents>1A", activeThreePhase.phaseSource());

        var unknownIr1 = reading(4, 0, 4);
        assertEquals(3, unknownIr1.effectivePhases(1));
        assertEquals("IR1023", unknownIr1.phaseSource());

        var noReportedPhaseCount = new ChargerReadings(0, 4, 0, 0, 0,
                new ChargerReadings.Phase(230, 0, 0), new ChargerReadings.Phase(230, 0, 0),
                new ChargerReadings.Phase(230, 0, 0), 0, 0, 0, 0);
        assertEquals(1, noReportedPhaseCount.effectivePhases(1));
        assertEquals("wallbox.phases", noReportedPhaseCount.phaseSource());
    }
}
