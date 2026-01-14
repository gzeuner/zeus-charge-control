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

    private BatteryCommandService commandService;
    private ChargingScheduleRepository chargingScheduleRepository;
    private BatteryManagementService service;

    @BeforeEach
    void setUp() {
        commandService = mock(BatteryCommandService.class);
        chargingScheduleRepository = mock(ChargingScheduleRepository.class);
        when(chargingScheduleRepository.findAll()).thenReturn(Collections.emptyList());

        service = new BatteryManagementService(commandService, chargingScheduleRepository);

        // Inject @Value defaults
        setField("manualHoldMs", 900_000L);
        setField("manualIdleHoldMs", 900_000L);
        setField("scheduleHoldMs", 600_000L);
        setField("defaultChargeWatt", 4600);
        setField("gridImportLimitWatt", 4600);
        setField("inverterMaxWattConfigured", 4600);
        setField("nightPauseWatts", 1);
        setField("targetStateOfCharge", 90);
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
