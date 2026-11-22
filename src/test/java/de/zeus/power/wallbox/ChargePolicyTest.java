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
        return new ChargerReadings(4, phases, evLimit, 0, new ChargerReadings.Phase(220, 0, 0),
                new ChargerReadings.Phase(230, 0, 0), new ChargerReadings.Phase(240, 0, 0),
                evKw, 12345, 1, installed);
    }

    private static SonnenReadings battery(double pv, double house, double pac, double soc, double backup) {
        return new SonnenReadings(pv, house, pv + pac - house, pac, soc, backup);
    }

    @Test
    void ecoRestoresMeteredEvLoadAndSubtractsDischarge() {
        var ev = charger(0, 3, 32, 16);
        var metered = battery(9000, 4400, 700, 90, 5);
        var separate = battery(9000, 1400, 700, 90, 5);
        assertEquals(10, policy(ECO, true, 0).target(ev, metered, 0));
        assertEquals(10, policy(ECO, false, 0).target(ev, separate, 0));
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
    void usesActivePhaseVoltageAndPausesBelowSixAmps() {
        var eco = policy(ECO, true, 0);
        for (int phase = 0; phase <= 5; phase++) {
            var ev = charger(phase, 0, 16, 16);
            double watts = new int[]{690, 220, 230, 240, 690, 470}[phase] * 6;
            assertEquals(0, eco.target(ev, battery(watts - 1, 0, 0, 50, 0), 0));
            assertEquals(6, eco.target(ev, battery(watts, 0, 0, 50, 0), 0));
        }
    }

    @Test
    void everyModeRespectsEvInstallationAndHardwareCeilings() {
        var sun = battery(40000, 0, 0, 90, 0);
        for (var mode : WallboxSettings.Mode.values()) {
            var policy = policy(mode, true, 0);
            assertEquals(13, policy.target(charger(0, 0, 13, 16), sun, 0));
            assertEquals(10, policy.target(charger(0, 0, 16, 10), sun, 0));
            assertEquals(16, policy.target(charger(0, 0, 32, 32), sun, 0));
            assertEquals(0, policy.target(charger(0, 0, 5, 16), sun, 0));
            assertEquals(0, policy.target(charger(0, 0, Float.NaN, 16), sun, 0));
        }
        assertEquals(16, policy(POWER, true, 0).target(charger(0, 0, 16, 16), null, 0));
    }

    @Test
    void sustainedSurplusIsRequiredAfterEveryPause() {
        var eco = policy(ECO, true, 16);
        var ev = charger(0, 0, 16, 16);
        var sun = battery(4140, 0, 0, 90, 0);
        long second = TimeUnit.SECONDS.toNanos(1);
        assertEquals(0, eco.target(ev, sun, 0));
        assertEquals(0, eco.target(ev, sun, 8 * second));
        assertEquals(6, eco.target(ev, sun, 16 * second));
        assertEquals(0, eco.target(ev, null, 24 * second));
        assertEquals(0, eco.target(ev, sun, 32 * second));
        assertEquals(6, eco.target(ev, sun, 48 * second));
        assertEquals(0, eco.target(ev, battery(4139, 0, 0, 90, 0), 56 * second));
    }

    @Test
    void onlyAnActiveConnectorInFastModeGetsAPositiveTarget() {
        var power = policy(POWER, true, 0);
        var ev = charger(0, 0, 16, 16);
        for (int state = 0; state <= 11; state++) {
            for (int mode : new int[]{1, 2}) {
                var reading = new ChargerReadings(state, 0, 16, 0, ev.l1(), ev.l2(), ev.l3(),
                        0, 0, mode, 16);
                boolean canCharge = switch (state) {
                    case 2, 3, 4, 5, 6, 9 -> mode == 1;
                    default -> false;
                };
                assertEquals(canCharge ? 16 : 0, power.target(reading, null, 0));
            }
        }
    }

    @Test
    void rejectsPollIntervalsOutsideTheWatchdogBudget() {
        for (int interval : new int[]{0, 8001}) {
            assertThrows(IllegalArgumentException.class, () -> new WallboxSettings("localhost", 502, 1,
                    ECO, interval, 20, 5, 0, true));
        }
    }

    @Test
    void missingOrInvalidSurplusMeasurementsPause() {
        var eco = policy(ECO, true, 0);
        assertEquals(0, eco.target(charger(0, 0, 16, 16),
                new SonnenReadings(8000.0, null, 0.0, 0.0, 70.0, 0.0), 0));
        assertEquals(0, eco.target(charger(0, Float.NaN, 16, 16), battery(8000, 0, 0, 70, 0), 0));
    }
}
