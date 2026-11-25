package de.zeus.power.wallbox;

/** Debounces measured phase counts so short current spikes do not change the ECO calculation. */
final class PhaseTracker {
    record Result(int phases, String source, int stableMeasuredPhases) {}

    private boolean inSession;
    private int candidate;
    private int candidatePolls;
    private int lowerCandidatePolls;
    private int stableMeasuredPhases;

    Result update(ChargerReadings readings, int configuredPhases) {
        boolean active = readings.status() >= 4 && readings.status() <= 7;
        if (!active) reset();
        else {
            if (!inSession) {
                inSession = true;
                candidate = 0;
                candidatePolls = 0;
                lowerCandidatePolls = 0;
                stableMeasuredPhases = 0;
            }
            observe(readings);
        }

        if (stableMeasuredPhases > 0)
            return new Result(stableMeasuredPhases, "measured-phase-currents", stableMeasuredPhases);
        if (readings.hardwarePhases() >= 1 && readings.hardwarePhases() <= 3)
            return new Result(readings.hardwarePhases(), "IR1023", 0);
        return new Result(configuredPhases, "wallbox.phases", 0);
    }

    private void observe(ChargerReadings readings) {
        int measured = readings.measuredActivePhases();
        float sum = readings.l1().amps() + readings.l2().amps() + readings.l3().amps();
        if (measured == 0 || !Float.isFinite(sum) || sum <= 3) {
            candidate = 0;
            candidatePolls = 0;
            lowerCandidatePolls = 0;
            return;
        }

        if (candidate == measured) candidatePolls++;
        else {
            candidate = measured;
            candidatePolls = 1;
            lowerCandidatePolls = 0;
        }
        if (stableMeasuredPhases == 0 || measured >= stableMeasuredPhases) {
            if (candidatePolls >= 3) stableMeasuredPhases = measured;
            lowerCandidatePolls = 0;
        } else {
            lowerCandidatePolls++;
            if (candidatePolls >= 3 && lowerCandidatePolls >= 5)
                stableMeasuredPhases = measured;
        }
    }

    private void reset() {
        inSession = false;
        candidate = 0;
        candidatePolls = 0;
        lowerCandidatePolls = 0;
        stableMeasuredPhases = 0;
    }
}
