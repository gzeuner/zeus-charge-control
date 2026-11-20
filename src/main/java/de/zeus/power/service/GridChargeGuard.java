package de.zeus.power.service;

import de.zeus.power.model.BatteryStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

@Service
public class GridChargeGuard {
    enum Mode { OFF, OBSERVE, ACTIVE }
    private static final Logger LOG = LoggerFactory.getLogger(GridChargeGuard.class);
    private final BatteryCommandService commands;
    private final Mode mode;
    private final ZoneId batteryZone;
    private long previousSample;
    private long destination;
    private boolean confirmed;
    private long leaseUntil;
    private long retryAfter;

    public GridChargeGuard(BatteryCommandService commands,
                           @Value("${scheduling.grid-guard.mode:ACTIVE}") Mode mode,
                           @Value("${battery.time-zone:Europe/Berlin}") ZoneId batteryZone) {
        this.commands = commands;
        this.mode = mode;
        this.batteryZone = batteryZone;
    }

    boolean enabled() { return mode != Mode.OFF; }

    synchronized long activeLeaseUntil(long now) {
        return leaseUntil > now ? leaseUntil : 0;
    }

    synchronized void reset() {
        previousSample = 0;
        destination = 0;
        confirmed = false;
    }

    synchronized void tick(long now, long target, long boundary, BatteryStatusResponse status) {
        long sample = sampleTime(status);
        if (!enabled() || sample <= 0 || sample > now + 2000 || now - sample > 45_000
                || status.getProductionW() > status.getConsumptionW()
                || boundary <= now || target <= now) {
            reset();
            return;
        }
        if (target != destination || sample < previousSample || sample - previousSample > 45_000) reset();
        if (sample == previousSample) return;
        destination = target;
        boolean renewing = now < leaseUntil;
        if (!status.isFlowGridBattery() && !(confirmed && renewing)) {
            reset();
            return;
        }
        boolean second = previousSample > 0;
        previousSample = sample;
        if (!second) return;
        if (!confirmed) LOG.info("Grid charging without an active Zeus hold conflicts with charging policy: operatingMode={}, RSOC={}%, next review={}, guard={}",
                status.getOperatingMode(), status.getRsoc(), Instant.ofEpochMilli(target), mode);
        confirmed = true;
        if (mode != Mode.ACTIVE || now < retryAfter) return;
        // Leave room for the HTTP round trip before the next plan boundary.
        int seconds = (int) Math.min(30, (boundary - System.currentTimeMillis()) / 1000 - 7);
        if (seconds < 1) return;
        if (commands.limitAcCharging(seconds, renewing)) {
            leaseUntil = System.currentTimeMillis() + seconds * 1000L;
            LOG.info("Temporary AC charge limit applied for {} seconds; operating mode unchanged.", seconds);
        } else {
            LOG.warn("Unable to apply AC charge limit; external charging may continue. Retrying after 60 seconds.");
            retryAfter = now + 60_000;
            reset();
        }
    }

    private long sampleTime(BatteryStatusResponse status) {
        if (status == null || status.getTimestamp() == null) return 0;
        String ts = status.getTimestamp().trim().replace(' ', 'T');
        try {
            return Instant.parse(ts).toEpochMilli();
        } catch (DateTimeParseException ex) {
            try {
                return LocalDateTime.parse(ts).atZone(batteryZone).toInstant().toEpochMilli();
            } catch (DateTimeParseException invalid) {
                return 0;
            }
        }
    }
}
