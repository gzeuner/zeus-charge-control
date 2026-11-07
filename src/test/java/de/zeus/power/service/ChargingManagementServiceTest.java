package de.zeus.power.service;

import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.event.MarketPricesUpdatedEvent;
import de.zeus.power.repository.MarketPriceRepository;
import de.zeus.power.repository.ChargingScheduleRepository;
import de.zeus.power.util.ChargingUtils;
import de.zeus.power.util.NightConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Date;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Ensures stop tasks are scheduled also for daytime windows and hand back to EMS when not in night idle.
 * Author: Guido Zeuner
 */
class ChargingManagementServiceTest {

    private ChargingManagementService service;
    private ChargingScheduleRepository scheduleRepo;
    private TaskScheduler taskScheduler;
    private BatteryManagementService bms;
    private ChargingUtils utils;
    private SeasonalTargetStateOfChargeService seasonalTargetStateOfChargeService;

    @BeforeEach
    void setUp() {
        scheduleRepo = mock(ChargingScheduleRepository.class);
        taskScheduler = mock(TaskScheduler.class);
        bms = mock(BatteryManagementService.class);
        utils = mock(ChargingUtils.class);
        seasonalTargetStateOfChargeService = mock(SeasonalTargetStateOfChargeService.class);
        when(seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge()).thenReturn(90);

        service = new ChargingManagementService();
        inject(service, "chargingScheduleRepository", scheduleRepo);
        inject(service, "marketPriceRepository", null);
        inject(service, "batteryManagementService", bms);
        inject(service, "taskScheduler", taskScheduler);
        inject(service, "chargingUtils", utils);
        inject(service, "weatherForecastService", null);
        inject(service, "seasonalTargetStateOfChargeService", seasonalTargetStateOfChargeService);
    }

    @Test
    void scheduleStopPlannedCharging_plansStopTasksForFutureSchedules() {
        long now = System.currentTimeMillis();
        ChargingSchedule s = new ChargingSchedule();
        s.setId(1L);
        s.setStartTimestamp(now + 10_000);
        s.setEndTimestamp(now + 20_000);
        when(scheduleRepo.findAll()).thenReturn(Collections.singletonList(s));
        when(taskScheduler.schedule(any(Runnable.class), any(Date.class))).thenReturn(mock(ScheduledFuture.class));
        when(utils.isNightChargingIdle()).thenReturn(false);
        when(bms.isManualIdleActive()).thenReturn(false);
        when(bms.resetToAutomaticMode(true)).thenReturn(true);

        service.scheduleStopPlannedCharging();

        verify(taskScheduler, atLeastOnce()).schedule(any(Runnable.class), any(Date.class));

        // Run captured runnable to assert it returns control to EMS for daytime
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(captor.capture(), any(Date.class));
        Runnable stopTask = captor.getValue();
        assertFalse(stopTask == null, "Stop task should be scheduled");

        stopTask.run();
        verify(bms, times(1)).resetToAutomaticMode(true);
    }

    @Test
    void scheduleNightIdleWindowTasks_enabledSchedulesStartAndEndTasks() {
        when(utils.isNightChargingIdle()).thenReturn(true);
        when(taskScheduler.schedule(any(Runnable.class), any(Date.class))).thenReturn(mock(ScheduledFuture.class));

        int hour = LocalDateTime.now().getHour();
        NightConfig.updateNightHours(hour, (hour + 1) % 24);

        service.scheduleNightIdleWindowTasks();

        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Date.class));
    }

    @Test
    void scheduleNightIdleWindowTasks_disabledDoesNotSchedule() {
        when(utils.isNightChargingIdle()).thenReturn(false);

        service.scheduleNightIdleWindowTasks();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Date.class));
    }

    @Test
    void activateNightIdleIfInWindow_activatesNightIdleHold() {
        when(bms.activateNightIdleHold()).thenReturn(true);

        int hour = LocalDateTime.now().getHour();
        NightConfig.updateNightHours(hour, (hour + 1) % 24);

        service.activateNightIdleIfInWindow();

        verify(bms).activateNightIdleHold();
    }

    @Test
    void activateNightIdleIfOutsideWindow_doesNothing() {
        int hour = LocalDateTime.now().getHour();
        NightConfig.updateNightHours((hour + 2) % 24, (hour + 3) % 24);

        service.activateNightIdleIfInWindow();

        verifyNoInteractions(bms);
    }

    @Test
    void recalculationKeepsEnoughWindowsEvenWhenAllPricesAreHighAndEqual() {
        int originalStart = NightConfig.getNightStartHour();
        int originalEnd = NightConfig.getNightEndHour();
        try {
            NightConfig.updateNightHours(0, 0);
            BatteryCapacityService capacity = mock(BatteryCapacityService.class);
            when(capacity.resolveTotalCapacityWh()).thenReturn(10_000);
            when(bms.getRelativeStateOfCharge()).thenReturn(10);
            when(bms.getRsocHistory()).thenReturn(List.of());
            ChargingUtils actualUtils = new ChargingUtils();
            inject(actualUtils, "batteryManagementService", bms);
            inject(actualUtils, "batteryCapacityService", capacity);
            inject(actualUtils, "seasonalTargetStateOfChargeService", seasonalTargetStateOfChargeService);
            inject(actualUtils, "chargingPointInWatt", 4600);
            inject(service, "chargingUtils", actualUtils);
            inject(service, "maxNighttimePeriods", 1);
            inject(service, "daytimeLookaheadHours", 12);
            inject(service, "additionAlphaCtPerMin", 0.02);

            long start = System.currentTimeMillis() + 60_000;
            MarketPrice first = price(start, 30.0);
            MarketPrice second = price(start + 3_600_000, 30.0);
            MarketPriceRepository prices = mock(MarketPriceRepository.class);
            when(prices.findAll()).thenReturn(List.of(first, second));
            inject(service, "marketPriceRepository", prices);

            List<ChargingSchedule> stored = new ArrayList<>();
            when(scheduleRepo.findAll()).thenAnswer(call -> new ArrayList<>(stored));
            when(scheduleRepo.save(any(ChargingSchedule.class))).thenAnswer(call -> {
                ChargingSchedule schedule = call.getArgument(0);
                schedule.setId((long) (stored.size() + 1));
                stored.add(schedule);
                return schedule;
            });
            doAnswer(call -> { stored.remove(call.getArgument(0)); return null; }).when(scheduleRepo).delete(any(ChargingSchedule.class));
            when(scheduleRepo.existsByStartEndAndPrice(anyLong(), anyLong(), anyDouble())).thenAnswer(call -> stored.stream().anyMatch(s ->
                    s.getStartTimestamp().equals(call.getArgument(0)) && s.getEndTimestamp().equals(call.getArgument(1))
                            && s.getPrice().equals(call.getArgument(2))));
            when(taskScheduler.schedule(any(Runnable.class), any(Date.class))).thenReturn(mock(ScheduledFuture.class));

            service.onMarketPricesUpdated(new MarketPricesUpdatedEvent(this));
            assertEquals(2, stored.size());
            assertEquals(List.of(first.getStartTimestamp(), second.getStartTimestamp()),
                    stored.stream().map(ChargingSchedule::getStartTimestamp).sorted().toList());
            service.onMarketPricesUpdated(new MarketPricesUpdatedEvent(this));
            assertEquals(2, stored.size());
            assertEquals(List.of(first.getStartTimestamp(), second.getStartTimestamp()),
                    stored.stream().map(ChargingSchedule::getStartTimestamp).sorted().toList());
        } finally {
            NightConfig.updateNightHours(originalStart, originalEnd);
        }
    }

    private static MarketPrice price(long start, double value) {
        MarketPrice price = new MarketPrice();
        price.setStartTimestamp(start);
        price.setEndTimestamp(start + 3_599_000);
        price.setMarketPrice(value);
        return price;
    }

    private static void inject(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
