package de.zeus.power.service;

import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryStatusResponse;
import de.zeus.power.repository.ChargingScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Verifies manual start behavior (user forced charging) including override of idle state.
 * Author: Guido Zeuner
 */
class BatteryManagementServiceTest {

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path stateDir;
    private BatteryCommandService commandService;
    private ChargingScheduleRepository chargingScheduleRepository;
    private SeasonalTargetStateOfChargeService seasonalTargetStateOfChargeService;
    private BatteryManagementService service;

    @BeforeEach
    void setUp() {
        commandService = mock(BatteryCommandService.class);
        chargingScheduleRepository = mock(ChargingScheduleRepository.class);
        seasonalTargetStateOfChargeService = mock(SeasonalTargetStateOfChargeService.class);
        when(chargingScheduleRepository.findAll()).thenReturn(Collections.emptyList());
        when(seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge()).thenReturn(90);

        service = new BatteryManagementService(commandService, chargingScheduleRepository);
        setField("seasonalTargetStateOfChargeService", seasonalTargetStateOfChargeService);
        setField("nightStatePath", stateDir.resolve("night.state").toString());

        // Inject @Value defaults
        setField("manualHoldMs", 900_000L);
        setField("manualIdleHoldMs", 900_000L);
        setField("scheduleHoldMs", 600_000L);
        setField("defaultChargeWatt", 4600);
        setField("gridImportLimitWatt", 4600);
        setField("inverterMaxWattConfigured", 4600);
        setField("nightPauseWatts", 1);
        setField("cacheDurationInSeconds", 60);
        setField("maxHistorySize", 24);
        setField("nightChargingIdle", true);

        // Provide cached battery status so the commandService does not need to be invoked for status
        BatteryStatusResponse status = new BatteryStatusResponse();
        status.setRsoc(50);
        status.setProductionW(0);
        status.setConsumptionW(0);
        status.setGridFeedInW(0);
        setField("cachedBatteryStatus", status);
        setField("cacheTimestamp", Instant.now());

        when(commandService.isBatteryNotConfigured()).thenReturn(false);
        when(commandService.getStatus()).thenReturn(new ApiResponse<>(true, HttpStatus.OK, "ok", status));
        when(commandService.setChargePoint(anyInt())).thenReturn(new ApiResponse<>(true, HttpStatus.OK, "ok", null));
        when(commandService.setNeutralSetPoint()).thenReturn(new ApiResponse<>(true, HttpStatus.OK, "ok", null));
    }

    @Test
    void pendingHandbackSurvivesRestartAndBlocksNewChargeUntilNeutralSucceeds() {
        when(commandService.setNeutralSetPoint()).thenReturn(
                new ApiResponse<>(false, HttpStatus.BAD_GATEWAY, "offline", null),
                new ApiResponse<>(false, HttpStatus.BAD_GATEWAY, "offline", null),
                new ApiResponse<>(true, HttpStatus.OK, "ok", null));
        assertFalse(service.disableNightIdleAndHandBack());
        BatteryManagementService restarted = new BatteryManagementService(commandService, chargingScheduleRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(restarted, "nightStatePath", stateDir.resolve("night.state").toString());
        org.springframework.test.util.ReflectionTestUtils.setField(restarted, "inverterMaxWattConfigured", 4600);
        org.springframework.test.util.ReflectionTestUtils.setField(restarted, "defaultChargeWatt", 4600);
        restarted.restoreNightState();
        assertTrue(restarted.isNightHandbackPending());
        assertFalse(restarted.isNightChargingIdle());
        assertFalse(restarted.setChargeWatts(2000));
        verify(commandService, never()).setChargePoint(anyInt());
        assertTrue(restarted.setChargeWatts(2000));
        assertFalse(restarted.isNightHandbackPending());
        assertTrue(restarted.retryNightHandback());
        verify(commandService, times(3)).setNeutralSetPoint();
        verify(commandService).setChargePoint(2000);
        BatteryManagementService nextRestart = new BatteryManagementService(commandService, chargingScheduleRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(nextRestart, "nightStatePath", stateDir.resolve("night.state").toString());
        nextRestart.restoreNightState();
        assertFalse(nextRestart.isNightHandbackPending());
        assertFalse(nextRestart.isNightChargingIdle());
    }

    @Test
    void scheduledNightWindowOverridesNightIdleAndCanRestoreItAfterwards() {
        int start = de.zeus.power.util.NightConfig.getNightStartHour();
        int end = de.zeus.power.util.NightConfig.getNightEndHour();
        int hour = java.time.LocalDateTime.now().getHour();
        try {
            de.zeus.power.util.NightConfig.updateNightHours(hour, (hour + 1) % 24);
            assertTrue(service.activateNightIdleHold());
            assertTrue(service.scheduleHoldTick());
            assertFalse(service.isNightIdleActive());
            verify(commandService).setChargePoint(4600);
            assertFalse(service.activateNightIdleHold());
            service.releaseScheduledOwner();
            assertTrue(service.activateNightIdleHold());
            verify(commandService, times(2)).setChargePoint(1);
        } finally { de.zeus.power.util.NightConfig.updateNightHours(start, end); }
    }

    @Test
    void failedNightEndHandbackSurvivesRestartWithoutDisablingNextNight() {
        service.setNightIdleActive(true);
        when(commandService.setNeutralSetPoint()).thenReturn(
                new ApiResponse<>(false, HttpStatus.BAD_GATEWAY, "offline", null),
                new ApiResponse<>(true, HttpStatus.OK, "ok", null));
        assertFalse(service.endNightIdleAndHandBack());
        BatteryManagementService restarted = new BatteryManagementService(commandService, chargingScheduleRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(restarted, "nightStatePath", stateDir.resolve("night.state").toString());
        restarted.restoreNightState();
        assertTrue(restarted.isNightHandbackPending());
        assertTrue(restarted.isNightChargingIdle());
        assertTrue(restarted.retryNightHandback());
        restarted.restoreNightState();
        assertTrue(restarted.isNightChargingIdle());
        assertFalse(restarted.isNightHandbackPending());
    }

    @Test
    void disableNightIdleDoesNotInterruptManualChargingOrPause() {
        service.setForcedChargingActive(true);
        assertTrue(service.disableNightIdleAndHandBack());
        service.setForcedChargingActive(false);
        service.setManualIdleActive(true);
        assertTrue(service.disableNightIdleAndHandBack());
        verify(commandService, never()).setNeutralSetPoint();
        assertTrue(service.isManualIdleActive());
    }

    @Test
    void nightHoldRemainsActiveAfterTemporaryCommandFailure() {
        int start = de.zeus.power.util.NightConfig.getNightStartHour();
        int end = de.zeus.power.util.NightConfig.getNightEndHour();
        int hour = java.time.LocalDateTime.now().getHour();
        try {
            de.zeus.power.util.NightConfig.updateNightHours(hour, (hour + 1) % 24);
            when(commandService.setChargePoint(1)).thenReturn(
                    new ApiResponse<>(false, HttpStatus.BAD_GATEWAY, "offline", null),
                    new ApiResponse<>(true, HttpStatus.OK, "ok", null));
            assertFalse(service.activateNightIdleHold());
            assertTrue(service.isNightIdleActive());
            assertTrue(service.startNightIdleHold());
            verify(commandService, times(2)).setChargePoint(1);
        } finally {
            de.zeus.power.util.NightConfig.updateNightHours(start, end);
        }
    }

    @Test
    void failedHandbackIsRetriedAndCannotReenableNightHold() {
        when(commandService.setNeutralSetPoint()).thenReturn(
                new ApiResponse<>(false, HttpStatus.BAD_GATEWAY, "offline", null),
                new ApiResponse<>(true, HttpStatus.OK, "ok", null));
        service.setNightIdleActive(true);
        assertFalse(service.disableNightIdleAndHandBack());
        assertFalse(service.isNightIdleActive());
        assertFalse(service.activateNightIdleHold());
        assertTrue(service.retryNightHandback());
        assertTrue(service.retryNightHandback());
        verify(commandService, times(2)).setNeutralSetPoint();
        verify(commandService, never()).setChargePoint(anyInt());
    }

    @Test
    void initCharging_forcedStart_clearsIdleAndSetsChargePoint() {
        service.setManualIdleActive(true);

        boolean started = service.initCharging(true);

        assertTrue(started, "Forced charging should start");
        assertFalse(service.isManualIdleActive(), "Manual idle must be cleared when user starts charging");
        assertTrue(service.isForcedChargingActive(), "Forced charging flag should be set");

        ArgumentCaptor<Integer> wattsCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(commandService).setChargePoint(wattsCaptor.capture());
        assertEquals(4600, wattsCaptor.getValue(), "Setpoint should use inverter/default max when status is available");
    }

    @Test
    void rejectedForcedStartPreservesManualPauseAndControlFlags() {
        service.setManualIdleActive(true);
        when(commandService.setChargePoint(anyInt())).thenReturn(new ApiResponse<>(false, HttpStatus.BAD_GATEWAY, "offline", null));

        assertFalse(service.initCharging(true));
        assertTrue(service.isManualIdleActive());
        assertFalse(service.isForcedChargingActive());
    }

    @Test
    void targetAlreadyReachedDoesNotClearManualPause() {
        service.setManualIdleActive(true);
        when(seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge()).thenReturn(40);

        assertFalse(service.initCharging(true));
        assertTrue(service.isManualIdleActive());
        verify(commandService, never()).setChargePoint(anyInt());
    }

    @Test
    void computeGridOptimizedChargeTarget_usesConservativeFallbackWhenStatusMissing() {
        setField("cachedBatteryStatus", null);
        when(commandService.getStatus()).thenReturn(new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "fail", null));

        int target = service.computeGridOptimizedChargeTarget();

        assertEquals(4600, target, "Fallback should use grid import limit when status unavailable");
    }

    private void setField(String name, Object value) {
        try {
            var field = BatteryManagementService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
