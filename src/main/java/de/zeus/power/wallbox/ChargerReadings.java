package de.zeus.power.wallbox;

record ChargerReadings(int status, int phases, int hardwarePhases, float evMaxAmps, float appliedAmps, Phase l1,
                       Phase l2, Phase l3, float powerKw, long lifetimeMwh, int selectedMode,
                       float installedMaxAmps) {

    record Phase(float volts, float amps, float kw) {}

    boolean sessionActive() {
        return status >= 4 && status <= 7 || status == 9;
    }

    boolean acceptsSetpoint() {
        // A setpoint is needed to let an attached vehicle leave "waiting for EV".
        // Keep faults, unavailable and unknown states fail-safe.
        return status >= 2 && status <= 7 || status == 9;
    }

    int effectivePhases(int configuredPhases) {
        int measured = measuredActivePhases();
        if (measured > 0) return measured;
        return hardwarePhases >= 1 && hardwarePhases <= 3 ? hardwarePhases : configuredPhases;
    }

    int measuredActivePhases() {
        int active = 0;
        if (l1.amps() > 1) active++;
        if (l2.amps() > 1) active++;
        if (l3.amps() > 1) active++;
        return active;
    }

    String phaseSource() {
        if (measuredActivePhases() > 0) return "measured-phase-currents>1A";
        if (hardwarePhases >= 1 && hardwarePhases <= 3) return "IR1023";
        return "wallbox.phases";
    }

    boolean enabled() {
        return appliedAmps > 0;
    }

    String appliedAmpsRawHex() {
        return rawFloatWords(appliedAmps);
    }

    String evMaxRawHex() {
        return rawFloatWords(evMaxAmps);
    }

    String installedMaxRawHex() {
        return rawFloatWords(installedMaxAmps);
    }

    private static String rawFloatWords(float value) {
        int bits = Float.floatToRawIntBits(value);
        return String.format(java.util.Locale.ROOT, "%04X %04X", (bits >>> 16) & 0xffff, bits & 0xffff);
    }

    String phaseDescription() {
        return switch (phases) {
            case 0 -> "3 (L1-L3)";
            case 1 -> "1 (L1)";
            case 2 -> "1 (L2)";
            case 3 -> "1 (L3)";
            case 4 -> "Unbekannt";
            case 5 -> "2";
            default -> "Unbekannt";
        };
    }

    String modeDescription() {
        return switch (selectedMode) {
            case 1 -> "Fast";
            case 2 -> "Smart";
            default -> "Keine Session / unbekannt";
        };
    }

    float maxAmps() {
        return maxAmps(6, 16);
    }

    float maxAmps(int min, int configuredMax) {
        float max = configuredMax;
        if (plausibleCeiling(installedMaxAmps, min)) max = Math.min(max, installedMaxAmps);
        // IR 2 is the vehicle's negotiated limit. Before a session it is commonly zero;
        // that means "not available", not a zero-amp capability.
        if ((sessionActive() || selectedMode == 1 || selectedMode == 2)
                && plausibleCeiling(evMaxAmps, min)) {
            max = Math.min(max, evMaxAmps);
        }
        return max >= min ? max : 0;
    }

    String ignoredCeilings(int min) {
        StringBuilder ignored = new StringBuilder();
        if (!plausibleCeiling(installedMaxAmps, min)) {
            ignored.append("ignored IR1028=").append(installedMaxAmps).append(" (unknown)");
        }
        if (!(sessionActive() || selectedMode == 1 || selectedMode == 2)) {
            append(ignored, "ignored IR2=" + evMaxAmps + " (no session)");
        } else if (!plausibleCeiling(evMaxAmps, min)) {
            append(ignored, "ignored IR2=" + evMaxAmps + " (unknown)");
        }
        return ignored.toString();
    }

    String limitingCeiling(int min, int configuredMax) {
        float ceiling = maxAmps(min, configuredMax);
        StringBuilder sources = new StringBuilder();
        if (ceiling == configuredMax) append(sources, "wallbox.max-current-a");
        if (plausibleCeiling(installedMaxAmps, min) && installedMaxAmps <= ceiling)
            append(sources, "IR1028");
        if ((sessionActive() || selectedMode == 1 || selectedMode == 2)
                && plausibleCeiling(evMaxAmps, min) && evMaxAmps <= ceiling)
            append(sources, "IR2");
        return sources.isEmpty() ? "wallbox.max-current-a" : sources.toString();
    }

    private static boolean plausibleCeiling(float value, int min) {
        return Float.isFinite(value) && value >= min;
    }

    private static void append(StringBuilder text, String value) {
        if (!text.isEmpty()) text.append(", ");
        text.append(value);
    }
}
