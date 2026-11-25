package de.zeus.power.wallbox;

import java.time.Duration;
import java.time.Instant;

enum WallboxSetpointStatus {
    KEINE_SESSION,
    WIRD_UEBERNOMMEN,
    AKTIV,
    BOX_IGNORIERT_SOLLWERT;

    static WallboxSetpointStatus evaluate(int status, Float commandedAmps, boolean writeAccepted,
                                         Instant flowStarted, Instant now, int acceptedWritesDuringFlow,
                                         ChargerReadings readings) {
        if (status >= 1 && status <= 3) return KEINE_SESSION;
        if (commandedAmps == null || readings == null || !Float.isFinite(readings.appliedAmps()))
            return WIRD_UEBERNOMMEN;

        int expected = commandedAmps >= 6 ? (int) Math.floor(commandedAmps) : 0;
        long flowingSeconds = flowStarted == null ? 0 : Math.max(0, Duration.between(flowStarted, now).getSeconds());
        float measuredPhaseCurrent = Math.max(readings.l1().amps(),
                Math.max(readings.l2().amps(), readings.l3().amps()));
        boolean ignored = status == 4 && writeAccepted && flowingSeconds > 45
                && acceptedWritesDuringFlow >= 2
                && "0000 0000".equals(readings.appliedAmpsRawHex())
                && Float.isFinite(measuredPhaseCurrent) && measuredPhaseCurrent > expected + 2;
        if (ignored) return BOX_IGNORIERT_SOLLWERT;
        if (Math.abs(readings.appliedAmps() - expected) < 0.1f) return AKTIV;

        // Status 5 means the vehicle has paused; otherwise an unresolved mismatch remains
        // in the handover state until the stricter, user-visible ignore criteria are met.
        return WIRD_UEBERNOMMEN;
    }
}
