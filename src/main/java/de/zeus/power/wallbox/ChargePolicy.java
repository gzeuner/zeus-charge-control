package de.zeus.power.wallbox;

import java.util.concurrent.TimeUnit;

final class ChargePolicy {
    private final WallboxSettings settings;
    private final double inverterMaxWatts;
    private Long surplusSince;
    private Long lowSince;
    private Double availableWatts;
    private String diagnostics = "";

    Double availableWatts() { return availableWatts; }
    String diagnostics() { return diagnostics; }

    ChargePolicy(WallboxSettings settings) { this(settings, 4600); }
    ChargePolicy(WallboxSettings settings, double inverterMaxWatts) {
        this.settings = settings;
        this.inverterMaxWatts = Math.max(0, inverterMaxWatts);
    }

    float target(ChargerReadings charger, SonnenReadings battery, long nowNanos) {
        return target(charger, battery, nowNanos, null);
    }

    float target(ChargerReadings charger, SonnenReadings battery, long nowNanos, Double pvOverrideW) {
        double pv = pvOverrideW != null && Double.isFinite(pvOverrideW) ? pvOverrideW
                : battery != null && battery.productionW() != null ? battery.productionW() : Double.NaN;
        double house = battery != null && battery.consumptionW() != null ? battery.consumptionW() : Double.NaN;
        double evW = charger != null && Float.isFinite(charger.powerKw()) && charger.powerKw() >= 0
                ? charger.powerKw() * 1000.0 : Double.NaN;
        double houseWithoutEv = house;
        String houseRule = "consumption-includes-wallbox=false";
        if (settings.consumptionIncludesWallbox()) {
            if (Double.isFinite(house) && Double.isFinite(evW)) {
                houseWithoutEv = Math.max(0, house - evW);
                houseRule = "house-minus-wallbox=" + round(houseWithoutEv) + "W";
            } else {
                houseWithoutEv = Double.NaN;
                houseRule = "house-with-wallbox=true, wallbox-power=missing";
            }
        }
        double pac = battery != null && battery.batteryW() != null ? battery.batteryW() : Double.NaN;
        double soc = battery != null && battery.usoc() != null ? battery.usoc() : Double.NaN;
        double grid = battery != null && battery.gridFeedInW() != null ? battery.gridFeedInW() : Double.NaN;
        double pvSurplus = Double.isFinite(pv) && Double.isFinite(houseWithoutEv) ? pv - houseWithoutEv : Double.NaN;
        double discharge = settings.mode() == WallboxSettings.Mode.SMART && battery != null && battery.aboveReserve(settings)
                && Double.isFinite(pac) ? Math.min(Math.max(0, pac), inverterMaxWatts) : 0;
        String batteryLimit = settings.mode() == WallboxSettings.Mode.SMART
                ? " [battery-discharge-limit=" + inverterMaxWatts + "W, allowed=" + round(discharge) + "W]" : "";
        double rawAvailable = switch (settings.mode()) {
            case POWER -> Double.NaN;
            case SMART -> Double.isFinite(pvSurplus) ? pvSurplus + discharge : Double.NaN;
            case ECO -> pvSurplus;
        };
        double wattsPerAmp = settings.phases() * (double) settings.voltage();
        float hardwareCeiling = charger == null ? 0 : charger.maxAmps(settings.minCurrentA(), settings.maxCurrentA());
        double ceiling = Math.min(settings.maxCurrentA(), hardwareCeiling);
        double rawAmps = settings.mode() == WallboxSettings.Mode.POWER ? ceiling
                : Double.isFinite(rawAvailable) ? rawAvailable / wattsPerAmp : Double.NaN;
        double amps = rawAmps;
        StringBuilder applied = new StringBuilder("[hardware/config max=" + ceiling + "A, wallbox.min-current-a="
                + settings.minCurrentA() + "A, wallbox.max-current-a=" + settings.maxCurrentA() + "A]")
                .append(batteryLimit);
        if (settings.mode() != WallboxSettings.Mode.POWER && Double.isFinite(amps) && amps > ceiling) {
            amps = ceiling;
            applied.append(" [max-current=" ).append(ceiling).append("A]");
        }
        if (settings.gridLimitWatts() > 0) {
            if (!Double.isFinite(houseWithoutEv) || !Double.isFinite(pv)) {
                amps = 0;
                applied.append(" [grid-limit=" ).append(settings.gridLimitWatts()).append("W, missing-meter=>0A]");
            } else {
                double batterySupply = Double.isFinite(pac) ? pac : 0;
                double siteImportWithoutEv = houseWithoutEv - pv - batterySupply;
                double gridBudgetForEv = Math.max(0, settings.gridLimitWatts() - siteImportWithoutEv);
                double cap = gridBudgetForEv / wattsPerAmp;
                if (amps > cap) amps = cap;
                applied.append(" [wallbox.grid-limit-watts=" ).append(settings.gridLimitWatts())
                        .append("W, cap=" ).append(round(cap)).append("A]");
            }
        }
        if (charger == null || !charger.sessionActive() || hardwareCeiling <= 0) {
            availableWatts = Double.isFinite(rawAvailable) ? rawAvailable : null;
            diagnostics = diagnostic(pv, house, houseWithoutEv, evW, pac, soc, grid, rawAmps, applied, 0, houseRule);
            return pause();
        }
        if (!Double.isFinite(amps)) {
            availableWatts = null;
            diagnostics = diagnostic(pv, house, houseWithoutEv, evW, pac, soc, grid, rawAmps, applied, 0, houseRule);
            return pause();
        }
        if (settings.mode() != WallboxSettings.Mode.POWER || settings.gridLimitWatts() > 0) {
            double rounded = Math.floor(amps * 10.0 + 1e-9) / 10.0;
            if (rounded != amps) applied.append(" [round-down-0.1A]");
            amps = rounded;
        }
        if (amps < settings.minCurrentA()) {
            availableWatts = rawAvailable;
            if (settings.mode() == WallboxSettings.Mode.POWER) {
                diagnostics = diagnostic(pv, house, houseWithoutEv, evW, pac, soc, grid, rawAmps, applied, 0, houseRule);
                return pause();
            }
            if (lowSince == null) lowSince = nowNanos;
            long hold = TimeUnit.SECONDS.toNanos(settings.resumeHoldSeconds());
            if (nowNanos - lowSince >= hold) {
                diagnostics = diagnostic(pv, house, houseWithoutEv, evW, pac, soc, grid, rawAmps, applied, 0, houseRule);
                return 0;
            }
            // Keep the existing setpoint during the stop hysteresis where possible.
            float held = charger.appliedAmps() >= settings.minCurrentA()
                    ? Math.min(charger.appliedAmps(), (float) ceiling) : 0;
            diagnostics = diagnostic(pv, house, houseWithoutEv, evW, pac, soc, grid, rawAmps,
                    applied.append(" [below-min=" ).append(settings.minCurrentA()).append("A, hysteresis]").toString(), held, houseRule);
            return held;
        }
        lowSince = null;
        if (settings.mode() != WallboxSettings.Mode.POWER && charger.appliedAmps() < settings.minCurrentA()) {
            if (surplusSince == null) surplusSince = nowNanos;
            long hold = TimeUnit.SECONDS.toNanos(settings.resumeHoldSeconds());
            if (nowNanos - surplusSince < hold) {
                diagnostics = diagnostic(pv, house, houseWithoutEv, evW, pac, soc, grid, rawAmps,
                        applied.append(" [resume-hold=" ).append(settings.resumeHoldSeconds()).append("s]").toString(), 0, houseRule);
                return 0;
            }
        } else {
            surplusSince = null;
        }
        availableWatts = settings.mode() == WallboxSettings.Mode.POWER ? null
                : Math.max(0, rawAvailable);
        diagnostics = diagnostic(pv, house, houseWithoutEv, evW, pac, soc, grid, rawAmps, applied, amps, houseRule);
        return (float) amps;
    }

    private String diagnostic(double pv, double house, double houseWithoutEv, double evW, double pac,
                              double soc, double grid, double rawAmps, CharSequence limits, double target, String houseRule) {
        return "mode=" + settings.mode() + " PV=" + round(pv) + "W house=" + round(house)
                + "W houseWithoutWallbox=" + round(houseWithoutEv) + "W wallbox=" + round(evW)
                + "W battery=" + round(pac) + "W USOC=" + round(soc) + "% grid=" + round(grid)
                + "W raw=" + round(rawAmps) + "A " + houseRule + " phases=" + settings.phases()
                + " voltage=" + settings.voltage() + "V battery.inverter.max.watts=" + inverterMaxWatts
                + "W " + limits + " final=" + round(target) + "A";
    }

    private static String round(double value) { return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.1f", value) : "n/a"; }

    float pause() { surplusSince = null; lowSince = null; return 0; }
}
