package de.zeus.power.wallbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WallboxSetpointStatusTest {
    private static ChargerReadings reading(int status, float applied, float current) {
        return new ChargerReadings(status, 6, 3, 6.1f, applied,
                new ChargerReadings.Phase(230, current, 0), new ChargerReadings.Phase(230, 0, 0),
                new ChargerReadings.Phase(230, 0, 0), 3.6f, 0, 1, 16);
    }

    @Test
    void classifiesNoSessionPendingActiveAndIgnoredReadbacks() {
        Instant now = Instant.parse("2026-11-25T10:00:46Z");
        Instant flowStarted = now.minusSeconds(44);
        assertEquals(WallboxSetpointStatus.KEINE_SESSION,
                WallboxSetpointStatus.evaluate(3, 6f, true, flowStarted, now, 2, reading(3, 0, 16)));
        assertEquals(WallboxSetpointStatus.WIRD_UEBERNOMMEN,
                WallboxSetpointStatus.evaluate(4, 6f, true, flowStarted, now, 2, reading(4, 0, 16)));
        assertEquals(WallboxSetpointStatus.AKTIV,
                WallboxSetpointStatus.evaluate(4, 6.9f, true, flowStarted, now, 2, reading(4, 6, 6)));
        assertEquals(WallboxSetpointStatus.AKTIV,
                WallboxSetpointStatus.evaluate(6, 0f, true, flowStarted, now, 2, reading(6, 0, 0)));
        assertEquals(WallboxSetpointStatus.BOX_IGNORIERT_SOLLWERT,
                WallboxSetpointStatus.evaluate(4, 6f, true, now.minusSeconds(46), now, 2, reading(4, 0, 16)));
    }

    @Test
    void ignoredStateRequiresMoreThan45SecondsTwoAcknowledgedWritesAndStatusFour() {
        Instant now = Instant.parse("2026-11-25T10:00:46Z");
        ChargerReadings fullCurrent = reading(4, 0, 16);
        assertEquals(WallboxSetpointStatus.WIRD_UEBERNOMMEN,
                WallboxSetpointStatus.evaluate(4, 6f, true, now.minusSeconds(44), now, 2, fullCurrent));
        assertEquals(WallboxSetpointStatus.WIRD_UEBERNOMMEN,
                WallboxSetpointStatus.evaluate(4, 6f, true, now.minusSeconds(46), now, 1, fullCurrent));
        assertEquals(WallboxSetpointStatus.WIRD_UEBERNOMMEN,
                WallboxSetpointStatus.evaluate(5, 6f, true, now.minusSeconds(46), now, 2,
                        reading(5, 0, 16)));
        assertEquals(WallboxSetpointStatus.WIRD_UEBERNOMMEN,
                WallboxSetpointStatus.evaluate(4, 6f, false, now.minusSeconds(46), now, 2, fullCurrent));
    }
}
