package de.zeus.power.service;

import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.repository.ChargingScheduleRepository;
import de.zeus.power.util.ChargingUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.util.Collections;
import java.util.Date;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Ensures stop tasks are scheduled also for daytime windows and hand back to EMS when not in night idle.
 */
class ChargingManagementServiceTest {

    private ChargingManagementService service;
    private ChargingScheduleRepository scheduleRepo;
    private TaskScheduler taskScheduler;
    private BatteryManagementService bms;
    private ChargingUtils utils;

    @BeforeEach
    void setUp() {
        scheduleRepo = mock(ChargingScheduleRepository.class);
        taskScheduler = mock(TaskScheduler.class);
        bms = mock(BatteryManagementService.class);
        utils = mock(ChargingUtils.class);

        service = new ChargingManagementService();
        inject(service, "chargingScheduleRepository", scheduleRepo);
        inject(service, "marketPriceRepository", null);
        inject(service, "batteryManagementService", bms);
        inject(service, "taskScheduler", taskScheduler);
        inject(service, "chargingUtils", utils);
        inject(service, "weatherForecastService", null);
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
