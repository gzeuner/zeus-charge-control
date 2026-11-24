package de.zeus.power.wallbox;

import java.util.concurrent.TimeUnit;

final class ChargePolicy {
    private final WallboxSettings settings;
    private final double inverterMaxWatts;
    private Long lowSince;
    private Double availableWatts;
    private String diagnostics = "";
    private double rawTargetAmps;
    private String clampReason = "none";

    Double availableWatts() { return availableWatts; }
    String diagnostics() { return diagnostics; }
    double rawTargetAmps() { return rawTargetAmps; }
    String clampReason() { return clampReason; }

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
        var mode = settings.effectiveMode();
        double discharge = mode == WallboxSettings.Mode.SMART && battery != null && battery.aboveReserve(settings)
                && Double.isFinite(pac) ? Math.min(Math.max(0, pac), inverterMaxWatts) : 0;
        String batteryLimit = mode == WallboxSettings.Mode.SMART
                ? " [battery-discharge-limit=" + inverterMaxWatts + "W, allowed=" + round(discharge) + "W]" : "";
        double rawAvailable = switch (mode) {
            case POWER -> Double.NaN;
            case SMART -> Double.isFinite(pvSurplus) ? pvSurplus + discharge : Double.NaN;
            case ECO -> pvSurplus;
        };
        int phases = charger == null ? settings.phases() : charger.effectivePhases(settings.phases());
        String phaseSource = charger == null ? "wallbox.phases" : charger.phaseSource();
        double wattsPerAmp = phases * (double) settings.voltage();
        float hardwareCeiling = charger == null ? 0 : charger.maxAmps(settings.minCurrentA(), settings.maxCurrentA());
        double ceiling = hardwareCeiling;
        double rawAmps = mode == WallboxSettings.Mode.POWER ? settings.maxCurrentA()
                : Double.isFinite(rawAvailable) ? rawAvailable / wattsPerAmp : Double.NaN;
        rawTargetAmps = rawAmps;
        clampReason = "none";
        double amps = rawAmps;
        String ignoredCeilings = charger == null ? "" : charger.ignoredCeilings(settings.minCurrentA());
        StringBuilder applied = new StringBuilder("[hardware/config max=" + ceiling + "A, phases=" + phases
                + " (source=" + phaseSource + ", IR1=" + (charger == null ? "n/a" : charger.phases()) + ", IR1023="
                + (charger == null ? "n/a" : charger.hardwarePhases()) + "), wallbox.min-current-a="
                + settings.minCurrentA() + "A, wallbox.max-current-a=" + settings.maxCurrentA() + "A]")
                .append(batteryLimit);
        if (!ignoredCeilings.isEmpty()) applied.append(" [").append(ignoredCeilings).append("]");
        if (Double.isFinite(amps) && amps > ceiling) {
            amps = ceiling;
            clampReason = charger.limitingCeiling(settings.minCurrentA(), settings.maxCurrentA());
            applied.append(" [max-current=" ).append(ceiling).append("A from ").append(clampReason).append("]");
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
                if (amps > cap) {
                    amps = cap;
                    clampReason = "wallbox.grid-limit-watts";
                }
                applied.append(" [wallbox.grid-limit-watts=" ).append(settings.gridLimitWatts())
                        .append("W, cap=" ).append(round(cap)).append("A]");
            }
        }
        if (charger == null || !charger.acceptsSetpoint() || hardwareCeiling <= 0) {
            availableWatts = Double.isFinite(rawAvailable) ? rawAvailable : null;
            diagnostics = diagnostic(pv, house, houseWithoutEv, evW, pac, soc, grid, charger, rawAmps, applied, 0, houseRule);
            return pause();
        }
        if (!Double.isFinite(amps)) {
            availableWatts = null;
            diagnostics = diagnostic(pv, house, houseWithoutEv, evW, pac, soc, grid, charger, rawAmps, applied, 0, houseRule);
            return pause();
        }
        if (mode != WallboxSettings.Mode.POWER || settings.gridLimitWatts() > 0) {
            double rounded = Math.floor(amps * 10.0 + 1e-9) / 10.0;
            if (rounded != amps) {
                applied.append(" [round-down-0.1A]");
                if ("none".equals(clampReason)) clampReason = "rounding";
            }
            amps = rounded;
        }
        if (amps < settings.minCurrentA()) {
            clampReason = "below-minimum-current";
            availableWatts = rawAvailable;
            if (mode == WallboxSettings.Mode.POWER) {
                diagnostics = diagnostic(pv, house, houseWithoutEv, evW, pac, soc, grid, charger, rawAmps, applied, 0, houseRule);
                return pause();
            }
            if (lowSince == null) lowSince = nowNanos;
            long hold = TimeUnit.SECONDS.toNanos(settings.resumeHoldSeconds());
            if (nowNanos - lowSince >= hold) {
                diagnostics = diagnostic(pv, house, houseWithoutEv, evW, pac, soc, grid, charger, rawAmps, applied, 0, houseRule);
                return 0;
            }
            // Keep the existing setpoint during the stop hysteresis where possible.
            float held = charger.appliedAmps() >= settings.minCurrentA()
                    ? Math.min(charger.appliedAmps(), (float) ceiling) : 0;
            if (held > 0) clampReason = "stop-hysteresis";
            diagnostics = diagnostic(pv, house, houseWithoutEv, evW, pac, soc, grid, charger, rawAmps,
                    applied.append(" [below-min=" ).append(settings.minCurrentA()).append("A, hysteresis]").toString(), held, houseRule);
            return held;
        }
        lowSince = null;
        // Start/resume as soon as the computed target reaches the charger's minimum.
        // The stop hysteresis above still prevents oscillation when available power falls.
        availableWatts = mode == WallboxSettings.Mode.POWER ? null
                : Math.max(0, rawAvailable);
        diagnostics = diagnostic(pv, house, houseWithoutEv, evW, pac, soc, grid, charger, rawAmps, applied, amps, houseRule);
        return (float) amps;
    }

    private String diagnostic(double pv, double house, double houseWithoutEv, double evW, double pac,
                              double soc, double grid, ChargerReadings charger, double rawAmps,
                              CharSequence limits, double target, String houseRule) {
        return "mode=" + settings.effectiveMode() + " PV=" + round(pv) + "W house=" + round(house)
                + "W houseWithoutWallbox=" + round(houseWithoutEv) + "W wallbox=" + round(evW)
                + "W battery=" + round(pac) + "W USOC=" + round(soc) + "% grid=" + round(grid)
                + "W raw=" + round(rawAmps) + "A " + houseRule + " phases=" + (charger == null
                ? settings.phases() + " (wallbox.phases)"
                : charger.effectivePhases(settings.phases()) + " (" + charger.phaseSource()
                        + ", IR1=" + charger.phases() + ", IR1023=" + charger.hardwarePhases() + ")")
                + " voltage=" + settings.voltage() + "V battery.inverter.max.watts=" + inverterMaxWatts
                + "W " + limits + " final=" + round(target) + "A";
    }

    private static String round(double value) { return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.1f", value) : "n/a"; }

    float pause() { lowSince = null; return 0; }
}
