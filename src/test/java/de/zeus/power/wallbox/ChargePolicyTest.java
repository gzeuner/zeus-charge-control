package de.zeus.power.wallbox;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static de.zeus.power.wallbox.WallboxSettings.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

class ChargePolicyTest {
    private static ChargePolicy policy(WallboxSettings.Mode mode, boolean includesEv, int hold) {
        return new ChargePolicy(new WallboxSettings("localhost", 502, 1, mode, 8000,
                25, 5, hold, includesEv));
    }

    private static ChargerReadings charger(int phases, float evKw, float evLimit, float installed) {
        return charger(4, 1, phases, evKw, evLimit, installed);
    }

    private static ChargerReadings charger(int status, int selectedMode, int phases, float evKw,
                                           float evLimit, float installed) {
        return new ChargerReadings(status, phases, 3, evLimit, 0, new ChargerReadings.Phase(220, 0, 0),
                new ChargerReadings.Phase(230, 0, 0), new ChargerReadings.Phase(240, 0, 0),
                evKw, 12345, selectedMode, installed);
    }

    private static ChargerReadings phaseReadings(int ir1, int hardware, int activePhases) {
        return new ChargerReadings(4, ir1, hardware, 16, 0,
                new ChargerReadings.Phase(230, activePhases >= 1 ? 2 : 0, 0),
                new ChargerReadings.Phase(230, activePhases >= 2 ? 2 : 0, 0),
                new ChargerReadings.Phase(230, activePhases >= 3 ? 2 : 0, 0),
                0, 0, 1, 16);
    }

    private static SonnenReadings battery(double pv, double house, double pac, double soc, double backup) {
        return new SonnenReadings(pv, house, pv + pac - house, pac, soc, backup);
    }

    @Test
    void ecoRestoresMeteredEvLoadAndSubtractsDischarge() {
        var ev = charger(0, 3, 32, 16);
        var metered = battery(9000, 4400, 700, 90, 5);
        var separate = battery(9000, 1400, 700, 90, 5);
        assertEquals(11, policy(ECO, true, 0).target(ev, metered, 0));
        assertEquals(11, policy(ECO, false, 0).target(ev, separate, 0));
        assertEquals(0, policy(ECO, false, 0).target(ev, battery(0, 1400, 700, 90, 5), 0));
    }

    @Test
    void smartAddsMeasuredDischargeOnlyAboveBothReserves() {
        var ev = charger(0, 0, 16, 16);
        var smart = policy(SMART, true, 0);
        assertEquals(10, smart.target(ev, battery(4650, 1200, 3450, 36, 30), 0));
        assertEquals(0, smart.target(ev, battery(4650, 1200, 3450, 35, 30), 0));
        assertEquals(0, smart.target(ev, battery(4650, 1200, 3450, 25, 5), 0));
        assertEquals(0, smart.target(ev, battery(4650, 1200, -3450, 90, 5), 0));
    }

    @Test
    void usesMeasuredActivePhasesThenHardwareFallbackAndPausesBelowSixAmps() {
        var eco = policy(ECO, true, 0);
        for (int phaseCount = 1; phaseCount <= 3; phaseCount++) {
            var ev = phaseReadings(5, 3, phaseCount);
            double watts = 230.0 * phaseCount * 6;
            assertEquals(0, eco.target(ev, battery(watts - 1, 0, 0, 50, 0), 0, null, null, phaseCount));
            assertEquals(6, eco.target(ev, battery(watts, 0, 0, 50, 0), 0, null, null, phaseCount));
        }
        assertEquals(3, phaseReadings(5, 3, 0).effectivePhases(1),
                "IR 1=5 is diagnostic only; IR 1023 is the no-current calculation fallback");
    }

    @Test
    void everyModeUsesOnlyConfiguredAndIr1028Ceilings() {
        var sun = battery(40000, 0, 0, 90, 0);
        for (var mode : WallboxSettings.Mode.values()) {
            var policy = policy(mode, true, 0);
            assertEquals(16, policy.target(charger(0, 0, 13, 16), sun, 0));
            assertEquals(10, policy.target(charger(0, 0, 16, 10), sun, 0));
            assertEquals(16, policy.target(charger(0, 0, 32, 32), sun, 0));
            assertEquals(16, policy.target(charger(0, 0, 5, 16), sun, 0));
            assertEquals(16, policy.target(charger(0, 0, Float.NaN, 16), sun, 0));
        }
        assertEquals(16, policy(POWER, true, 0).target(charger(0, 0, 16, 16), null, 0));
    }

    @Test
    void sufficientSurplusStartsImmediatelyAndLowPowerStillStopsWithHysteresis() {
        var eco = policy(ECO, true, 16);
        var ev = charger(0, 0, 16, 16);
        var sun = battery(4140, 0, 0, 90, 0);
        long second = TimeUnit.SECONDS.toNanos(1);
        assertEquals(6, eco.target(ev, sun, 0));
        assertEquals(6, eco.target(ev, sun, 8 * second));
        assertEquals(6, eco.target(ev, sun, 16 * second));
        var active = new ChargerReadings(4, 0, 3, 16, 6, ev.l1(), ev.l2(), ev.l3(), 1.4f, 0, 1, 16);
        assertEquals(6, eco.target(active, battery(4139, 0, 0, 90, 0), 24 * second, null, 6f, 3));
        assertEquals(6, eco.target(active, battery(4139, 0, 0, 90, 0), 32 * second, null, 6f, 3));
        assertEquals(0, eco.target(active, battery(4139, 0, 0, 90, 0), 40 * second, null, 6f, 3));
        assertEquals(6, eco.target(ev, sun, 48 * second));
        assertEquals(6, eco.target(ev, battery(4139, 0, 0, 90, 0), 56 * second, null, 6f, 3));
        assertEquals(0, eco.target(ev, battery(4139, 0, 0, 90, 0), 72 * second, null, 6f, 3));
    }

    @Test
    void onlyAnActiveConnectorGetsAPositiveTargetAndIr53IsDiagnosticOnly() {
        var power = policy(POWER, true, 0);
        var ev = charger(0, 0, 16, 16);
        for (int state = 0; state <= 11; state++) {
            for (int mode : new int[]{1, 2}) {
                var reading = new ChargerReadings(state, 0, 3, 16, 0, ev.l1(), ev.l2(), ev.l3(),
                        0, 0, mode, 16);
                boolean canCharge = switch (state) {
                    case 2, 3, 4, 5, 6, 7, 9 -> true;
                    default -> false;
                };
                assertEquals(canCharge ? 16 : 0, power.target(reading, null, 0));
            }
        }
    }

    @Test
    void rejectsPollIntervalsOutsideTheWatchdogBudget() {
        for (int interval : new int[]{0, 8001}) {
            var invalid = new WallboxSettings("localhost", 502, 1, ECO, interval, 20, 5, 0, true);
            assertTrue(invalid.validationError().contains("mode, timing"));
        }
    }

    @Test
    void missingOrInvalidSurplusMeasurementsPause() {
        var eco = policy(ECO, true, 0);
        assertEquals(0, eco.target(charger(0, 0, 16, 16),
                new SonnenReadings(8000.0, null, 0.0, 0.0, 70.0, 0.0), 0));
        assertEquals(0, eco.target(charger(0, Float.NaN, 16, 16), battery(8000, 0, 0, 70, 0), 0));
    }

    @Test
    void freshKostalPvCanReplaceSonnenProductionAndNullFallsBackToSonnen() {
        var eco = policy(ECO, true, 0);
        var ev = charger(0, 0, 16, 16);
        var battery = battery(1000, 500, 0, 70, 0);
        assertEquals(6, eco.target(ev, battery, 0, 4640.0));
        assertEquals(0, eco.target(ev, battery, 0, null));
    }

    @Test
    void powerIgnoresBatteryGridImportLimitAndUsesOnlyOptionalSiteLimit() {
        var ev = charger(0, 0, 16, 16);
        var noLimit = policy(POWER, true, 0);
        assertEquals(16, noLimit.target(ev, battery(0, 4600, 4600, 90, 5), 0));

        var base = new WallboxSettings("localhost", 502, 1, POWER, 8000, 25, 5, 0, true,
                2000, 2500, 3, 3, 230, 6, 16, 4600);
        var limited = new ChargePolicy(base);
        assertEquals(6, limited.target(ev, battery(0, 0, 0, 90, 5), 0), 0.01);
        assertEquals(0, limited.target(ev, battery(0, 2000, 0, 90, 5), 0));

        assertEquals(6.0f, policy(POWER, true, 0)
                .target(charger(0, 0, 16, 6.032f), null, 0), 0.001f);
    }

    @Test
    void smartCapsBatteryDischargeByInverterLimitAndStopsUsingItBelowReserve() {
        var ev = charger(0, 0, 16, 16);
        var settings = new WallboxSettings("localhost", 502, 1, SMART, 8000, 20, 5, 0, true);
        var smart = new ChargePolicy(settings, 3000);
        var loadedEv = charger(0, 4, 16, 16);
        assertEquals(7, smart.target(loadedEv, battery(2800, 4720, 4000, 92, 5), 0), 0.01);
        assertEquals(0, smart.target(loadedEv, battery(2800, 4720, 4000, 19, 5), 0));
    }

    @Test
    void wallboxConsumptionIsRemovedExactlyOnce() {
        var ev = charger(0, 4, 16, 16);
        var includes = policy(ECO, true, 0);
        var separate = policy(ECO, false, 0);
        float fromMeteredHouse = includes.target(ev, battery(5000, 4720, 0, 90, 5), 0);
        float fromSeparateHouse = separate.target(ev, battery(5000, 720, 0, 90, 5), 0);
        assertEquals(fromSeparateHouse, fromMeteredHouse);
        assertEquals(6, fromMeteredHouse, 0.01);
    }

    @Test
    void stopWaitsForHysteresisButDoesNotStartBelowMinimum() {
        var eco = policy(ECO, true, 16);
        var active = new ChargerReadings(4, 0, 3, 16, 10, new ChargerReadings.Phase(230, 0, 0),
                new ChargerReadings.Phase(230, 0, 0), new ChargerReadings.Phase(230, 0, 0),
                4, 0, 1, 16);
        long sec = TimeUnit.SECONDS.toNanos(1);
        assertEquals(10, eco.target(active, battery(3000, 0, 0, 90, 0), 0, null, 10f, 3));
        assertEquals(10, eco.target(active, battery(1000, 0, 0, 90, 0), sec, null, 10f, 3));
        assertEquals(0, eco.target(active, battery(1000, 0, 0, 90, 0), 16 * sec, null, 10f, 3));
        assertEquals(0, eco.target(charger(3, 0, 16, 16), battery(1000, 0, 0, 90, 0), 20 * sec));
    }

    @Test
    void configuredPhaseVoltageAndCurrentBoundsAreUsed() {
        var settings = new WallboxSettings("localhost", 502, 1, ECO, 8000, 20, 5, 0, true,
                2000, 2500, 3, 1, 240, 7, 12, 0);
        var policy = new ChargePolicy(settings);
        var configuredPhaseFallback = phaseReadings(1, 0, 0);
        assertEquals(7, policy.target(configuredPhaseFallback, battery(1700, 0, 0, 90, 0), 0));
        assertEquals(12, policy.target(configuredPhaseFallback, battery(10000, 0, 0, 90, 0), 0));
    }

    @Test
    void zeroOrMissingRegisterCeilingsDoNotDeadlockBeforeVehicleSession() {
        var waiting = charger(3, 0, 4, 0, 0, 16); // Waiting for EV; IR 2 and IR 4 are not negotiated yet.
        assertEquals(16, policy(POWER, true, 0).target(waiting, null, 0));
        assertEquals(16, policy(POWER, true, 0).target(
                charger(3, 0, 4, 0, 0, 0), null, 0)); // IR 1028=0 is unknown too.

        var eco = policy(ECO, true, 0);
        assertEquals(8, eco.target(waiting, battery(5600, 0, 0, 90, 0), 0), 0.01);
    }

    @Test
    void ir2IsDisplayOnlyAndNeverLimitsTheSetpoint() {
        var power = policy(POWER, true, 0);
        assertEquals(16, power.target(charger(3, 0, 4, 0, 6.1f, 16), null, 0));
        assertEquals(16, power.target(charger(4, 1, 4, 0, 6.1f, 16), null, 0));
        assertEquals(10, power.target(charger(4, 1, 4, 0, 6.1f, 10), null, 0));
        assertEquals(16, power.target(charger(4, 1, 4, 0, Float.NaN, 0), null, 0));
    }

    @Test
    void hr8TargetsAreFlooredAndEcoHysteresisKeepsTheSameInteger() {
        var eco = policy(ECO, false, 0);
        var ev = charger(4, 1, 16, 0, 16, 16);
        assertEquals(6, eco.target(ev, battery(6.4 * 690, 0, 0, 90, 0), 0, null, 6f, 3));
        assertEquals(6, eco.target(ev, battery(6.6 * 690, 0, 0, 90, 0), 1, null, 6f, 3));
        assertEquals(6, eco.target(ev, battery(7.4 * 690, 0, 0, 90, 0), 2, null, 6f, 3));
        assertEquals(7, eco.target(ev, battery(7.6 * 690, 0, 0, 90, 0), 3, null, 6f, 3));
        assertEquals(7, eco.target(ev, battery(6.6 * 690, 0, 0, 90, 0), 4, null, 7f, 3));
        assertEquals(6, eco.target(ev, battery(6.4 * 690, 0, 0, 90, 0), 5, null, 7f, 3));

        var pause = policy(ECO, false, 0);
        assertEquals(0, pause.target(ev, battery(5.9 * 690, 0, 0, 90, 0), 0, null, 6f, 3));
    }

    @Test
    void ir1UnknownFallsBackToHardwarePhasesForPowerCalculation() {
        var settings = new WallboxSettings("localhost", 502, 1, ECO, 8000, 20, 5, 0, true,
                2000, 2500, 3, 1, 230, 6, 16, 0);
        var eco = new ChargePolicy(settings);
        var unknownPhasesButThreePhaseHardware = charger(3, 0, 4, 0, 0, 16);
        assertEquals(8, eco.target(unknownPhasesButThreePhaseHardware,
                battery(5600, 0, 0, 90, 0), 0), 0.01);
    }

    @Test
    void ecoUsesMeasuredThreePhaseCurrentsAheadOfIr1TwoPhaseValue() {
        var settings = new WallboxSettings("localhost", 502, 1, ECO, 8000, 20, 5, 0, true,
                2000, 2500, 3, 3, 230, 6, 16, 0);
        var ev = new ChargerReadings(4, 5, 3, 16, 0,
                new ChargerReadings.Phase(230, 15.9f, 0),
                new ChargerReadings.Phase(230, 15.9f, 0),
                new ChargerReadings.Phase(230, 15.9f, 0),
                11.07f, 0, 1, 16);
        assertEquals(6.0f, new ChargePolicy(settings)
                .target(ev, battery(4565, 0, 0, 90, 0), 0), 0.01);
    }
}
