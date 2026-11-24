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
}
