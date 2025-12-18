package de.zeus.power.util;

import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.service.BatteryManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the wait-for-cheaper-window heuristic to ensure it selects valid cheaper slots
 * and respects RSOC floor constraints.
 * Author: User
 */
class ChargingUtilsTest {

    private ChargingUtils utils;
    private BatteryManagementService bms;

    @BeforeEach
    void setUp() {
        bms = mock(BatteryManagementService.class);
        utils = new ChargingUtils();
        inject(utils, "batteryManagementService", bms);
        inject(utils, "maxCapacityInWatt", 10_000);
        inject(utils, "targetStateOfCharge", 90);
        inject(utils, "chargingPointInWatt", 4600);
        inject(utils, "waitMinSavingsCt", 1.0);
        inject(utils, "waitMinSavingsPct", 0.05);
        inject(utils, "waitMaxDelayMinutes", 120);
        inject(utils, "rsocMinFloor", 15);
        inject(utils, "defaultRsocDropPerHour", 3.0);
    }

    @Test
    void shouldWaitForCheaperWindow_whenCheaperSoonAndRsocSafe() {
        long now = System.currentTimeMillis();
        ChargingSchedule current = schedule(now + 10_000, now + 3_610_000, 13.0);
        ChargingSchedule cheaperSoon = schedule(now + 30_000, now + 3_630_000, 6.0);
        List<ChargingSchedule> future = List.of(cheaperSoon, current);

        when(bms.getRelativeStateOfCharge()).thenReturn(50);
        when(bms.getRsocHistory()).thenReturn(history(now, 50, 49)); // mild drop

        Optional<ChargingSchedule> decision = utils.shouldWaitForCheaperWindow(current, future, bms);

        assertTrue(decision.isPresent(), "Cheaper near-term window should be chosen");
        assertEquals(cheaperSoon.getStartTimestamp(), decision.get().getStartTimestamp());
    }

    @Test
    void shouldNotWait_whenRsocWouldDropBelowFloor() {
        long now = System.currentTimeMillis();
        ChargingSchedule current = schedule(now + 10_000, now + 3_610_000, 13.0);
        ChargingSchedule cheaperLate = schedule(now + 90 * 60_000, now + 93 * 60_000, 6.0); // 90 min later
        List<ChargingSchedule> future = List.of(cheaperLate, current);

        when(bms.getRelativeStateOfCharge()).thenReturn(20);
        // Fast drop history -> projection below floor
        when(bms.getRsocHistory()).thenReturn(history(now - 3_600_000, 40, 20));

        Optional<ChargingSchedule> decision = utils.shouldWaitForCheaperWindow(current, future, bms);

        assertTrue(decision.isEmpty(), "Should not wait if RSOC projection violates safety floor");
    }

    private ChargingSchedule schedule(long start, long end, double price) {
        ChargingSchedule s = new ChargingSchedule();
        s.setStartTimestamp(start);
        s.setEndTimestamp(end);
        s.setPrice(price);
        return s;
    }

    private List<Map.Entry<Long, Integer>> history(long base, int startRsoc, int endRsoc) {
        List<Map.Entry<Long, Integer>> h = new ArrayList<>();
        h.add(Map.entry(base, startRsoc));
        h.add(Map.entry(base + 3_000, endRsoc));
        return h;
    }

    private static void inject(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
