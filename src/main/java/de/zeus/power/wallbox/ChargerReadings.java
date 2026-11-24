package de.zeus.power.wallbox;

record ChargerReadings(int status, int phases, int hardwarePhases, float evMaxAmps, float appliedAmps, Phase l1,
                       Phase l2, Phase l3, float powerKw, long lifetimeMwh, int selectedMode,
                       float installedMaxAmps) {

    record Phase(float volts, float amps, float kw) {}

    boolean sessionActive() {
        return status >= 2 && status <= 6 || status == 9;
    }

    boolean enabled() {
        return appliedAmps > 0;
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

    double wattsPerAmp() {
        double a = l1.volts(), b = l2.volts(), c = l3.volts();
        return switch (phases) {
            case 1 -> a;
            case 2 -> b;
            case 3 -> c;
            // IR 1 does not identify the active pair. Budget for the two highest voltages.
            case 5 -> valid(a, b, c) ? a + b + c - Math.min(a, Math.min(b, c)) : 0;
            // During detection the eventual phase count is unknown. Budget for three.
            case 0, 4 -> valid(a, b, c) ? a + b + c : 0;
            default -> 0;
        };
    }

    private static boolean valid(double a, double b, double c) {
        return Double.isFinite(a + b + c) && a > 0 && b > 0 && c > 0;
    }

    float maxAmps() {
        return maxAmps(6, 16);
    }

    float maxAmps(int min, int configuredMax) {
        float max = Math.min(configuredMax, Math.min(installedMaxAmps, evMaxAmps));
        return Float.isFinite(installedMaxAmps) && Float.isFinite(evMaxAmps) && max >= min ? max : 0;
    }
}
