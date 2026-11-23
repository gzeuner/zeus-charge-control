package de.zeus.power.wallbox;

import java.util.concurrent.TimeUnit;

final class ChargePolicy {
    private final WallboxSettings settings;
    private Long surplusSince;
    private Double availableWatts;

    Double availableWatts() {
        return availableWatts;
    }

    ChargePolicy(WallboxSettings settings) {
        this.settings = settings;
    }

    float target(ChargerReadings charger, SonnenReadings battery, long nowNanos) {
        return target(charger, battery, nowNanos, null);
    }

    float target(ChargerReadings charger, SonnenReadings battery, long nowNanos, Double pvOverrideW) {
        availableWatts = available(charger, battery, pvOverrideW);
        float ceiling = charger.maxAmps();
        if (charger.selectedMode() != 1 || !charger.sessionActive() || ceiling == 0) {
            return pause();
        }
        if (settings.mode() == WallboxSettings.Mode.POWER) {
            return ceiling;
        }
        double wattsPerAmp = charger.wattsPerAmp();
        if (availableWatts == null || !Double.isFinite(wattsPerAmp) || wattsPerAmp <= 0) {
            return pause();
        }
        double amps = Math.min(ceiling, availableWatts / wattsPerAmp);
        if (amps < 6) {
            return pause();
        }
        if (surplusSince == null) {
            surplusSince = nowNanos;
        }
        long hold = TimeUnit.SECONDS.toNanos(settings.resumeHoldSeconds());
        return nowNanos - surplusSince >= hold ? (float) amps : 0;
    }

    private Double available(ChargerReadings charger, SonnenReadings battery, Double pvOverrideW) {
        if (settings.mode() == WallboxSettings.Mode.POWER || battery == null || !battery.hasPowerReadings()) {
            return null;
        }
        double houseW = battery.consumptionW();
        if (settings.consumptionIncludesWallbox()) {
            if (!Float.isFinite(charger.powerKw()) || charger.powerKw() < 0) {
                return null;
            }
            // Samples are taken on different devices. House load cannot be negative.
            houseW = Math.max(0, houseW - charger.powerKw() * 1000.0);
        }
        double dischargeW = Math.max(0, battery.batteryW());
        double productionW = pvOverrideW != null && Double.isFinite(pvOverrideW) ? pvOverrideW : battery.productionW();
        double pvSurplusW = productionW - houseW;
        return settings.mode() == WallboxSettings.Mode.SMART && battery.aboveReserve(settings)
                ? pvSurplusW + dischargeW : pvSurplusW - dischargeW;
    }

    float pause() {
        surplusSince = null;
        return 0;
    }
}
