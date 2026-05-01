package de.zeus.power.service;

import de.zeus.power.model.BatteryCapacitySnapshot;
import de.zeus.power.model.BatteryStatusMetrics;
import de.zeus.power.model.BatteryStatusSample;
import de.zeus.power.model.BatteryStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatteryStatusMetricsServiceTest {

    private BatteryManagementService batteryManagementService;
    private BatteryCapacityService batteryCapacityService;
    private SeasonalTargetStateOfChargeService seasonalTargetStateOfChargeService;
    private BatteryStatusMetricsService service;

    @BeforeEach
    void setUp() {
        batteryManagementService = mock(BatteryManagementService.class);
        batteryCapacityService = mock(BatteryCapacityService.class);
        seasonalTargetStateOfChargeService = mock(SeasonalTargetStateOfChargeService.class);
        service = new BatteryStatusMetricsService(
                batteryManagementService,
                batteryCapacityService,
                seasonalTargetStateOfChargeService
        );
    }

    @Test
    void calculateCurrentMetrics_dischargingHistory_returnsDropRateAndNoEtaToLowerTarget() {
        BatteryStatusResponse currentStatus = batteryStatus(80, 8_000, 1_000, false, true);
        mockCapacitySnapshot(currentStatus);
        when(batteryManagementService.getCurrentBatteryStatus()).thenReturn(currentStatus);
        when(batteryManagementService.getBatteryStatusHistory()).thenReturn(List.of(
                new BatteryStatusSample(0L, 82, 8_200, 1_000, false, true),
                new BatteryStatusSample(3_600_000L, 81, 8_100, 1_000, false, true),
                new BatteryStatusSample(7_200_000L, 80, 8_000, 1_000, false, true)
        ));
        when(seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge()).thenReturn(70);

        BatteryStatusMetrics metrics = service.calculateCurrentMetrics();

        assertNotNull(metrics.dropRatePerHour());
        assertEquals(1.25, metrics.dropRatePerHour(), 0.0001);
        assertNull(metrics.estimatedTimeToTargetHours());
    }

    @Test
    void calculateCurrentMetrics_flatHistoryButActiveDischarge_usesLivePowerFallbackAndReturnsNoEta() {
        BatteryStatusResponse currentStatus = batteryStatus(80, 8_000, 1_000, false, true);
        mockCapacitySnapshot(currentStatus);
        when(batteryManagementService.getCurrentBatteryStatus()).thenReturn(currentStatus);
        when(batteryManagementService.getBatteryStatusHistory()).thenReturn(List.of(
                new BatteryStatusSample(0L, 80, 8_000, 0, false, true),
                new BatteryStatusSample(180_000L, 80, 8_000, 0, false, true)
        ));
        when(seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge()).thenReturn(70);

        BatteryStatusMetrics metrics = service.calculateCurrentMetrics();

        assertEquals(12.5, metrics.dropRatePerHour(), 0.0001);
        assertNull(metrics.estimatedTimeToTargetHours());
    }

    @Test
    void calculateCurrentMetrics_dropRateUsesRemainingCapacityAsReference() {
        BatteryStatusResponse currentStatus = batteryStatus(80, 8_000, 295, false, true);
        mockCapacitySnapshot(currentStatus);
        when(batteryManagementService.getCurrentBatteryStatus()).thenReturn(currentStatus);
        when(batteryManagementService.getBatteryStatusHistory()).thenReturn(List.of());
        when(seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge()).thenReturn(70);

        BatteryStatusMetrics metrics = service.calculateCurrentMetrics();

        assertEquals(3.6875, metrics.dropRatePerHour(), 0.0001);
        assertNull(metrics.estimatedTimeToTargetHours());
    }

    @Test
    void calculateCurrentMetrics_belowTargetWhileCharging_returnsEtaAndNoDropRate() {
        BatteryStatusResponse currentStatus = batteryStatus(60, 6_000, 500, true, false);
        mockCapacitySnapshot(currentStatus);
        when(batteryManagementService.getCurrentBatteryStatus()).thenReturn(currentStatus);
        when(batteryManagementService.getBatteryStatusHistory()).thenReturn(List.of(
                new BatteryStatusSample(0L, 60, 6_000, 500, true, false),
                new BatteryStatusSample(3_600_000L, 65, 6_500, 500, true, false)
        ));
        when(seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge()).thenReturn(70);

        BatteryStatusMetrics metrics = service.calculateCurrentMetrics();

        assertNull(metrics.dropRatePerHour());
        assertEquals(2.0, metrics.estimatedTimeToTargetHours(), 0.0001);
    }

    @Test
    void calculateCurrentMetrics_belowTargetWhileDischarging_returnsNoEta() {
        BatteryStatusResponse currentStatus = batteryStatus(66, 6_600, 88, false, true);
        mockCapacitySnapshot(currentStatus);
        when(batteryManagementService.getCurrentBatteryStatus()).thenReturn(currentStatus);
        when(batteryManagementService.getBatteryStatusHistory()).thenReturn(List.of(
                new BatteryStatusSample(0L, 68, 6_800, 90, false, true),
                new BatteryStatusSample(3_600_000L, 67, 6_700, 88, false, true),
                new BatteryStatusSample(7_200_000L, 66, 6_600, 88, false, true)
        ));
        when(seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge()).thenReturn(70);

        BatteryStatusMetrics metrics = service.calculateCurrentMetrics();

        assertNotNull(metrics.dropRatePerHour());
        assertNull(metrics.estimatedTimeToTargetHours());
    }

    @Test
    void calculateCurrentMetrics_dischargingWithContradictingHistory_usesLiveFallbackAndReturnsNoEta() {
        BatteryStatusResponse currentStatus = batteryStatus(66, 6_600, 88, false, true);
        mockCapacitySnapshot(currentStatus);
        when(batteryManagementService.getCurrentBatteryStatus()).thenReturn(currentStatus);
        when(batteryManagementService.getBatteryStatusHistory()).thenReturn(List.of(
                new BatteryStatusSample(0L, 66, 6_500, 88, false, true),
                new BatteryStatusSample(3_600_000L, 66, 6_550, 88, false, true),
                new BatteryStatusSample(7_200_000L, 66, 6_600, 88, false, true)
        ));
        when(seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge()).thenReturn(70);

        BatteryStatusMetrics metrics = service.calculateCurrentMetrics();

        assertEquals(1.3333333333, metrics.dropRatePerHour(), 0.0001);
        assertNull(metrics.estimatedTimeToTargetHours());
    }

    @Test
    void calculateCurrentMetrics_targetReached_returnsNoEta() {
        BatteryStatusResponse currentStatus = batteryStatus(70, 7_000, 0, false, false);
        mockCapacitySnapshot(currentStatus);
        when(batteryManagementService.getCurrentBatteryStatus()).thenReturn(currentStatus);
        when(batteryManagementService.getBatteryStatusHistory()).thenReturn(List.of());
        when(seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge()).thenReturn(70);

        BatteryStatusMetrics metrics = service.calculateCurrentMetrics();

        assertEquals(0.0, metrics.dropRatePerHour(), 0.0001);
        assertNull(metrics.estimatedTimeToTargetHours());
    }

    @Test
    void calculateCurrentMetrics_targetAlreadyExceeded_returnsNoEta() {
        BatteryStatusResponse currentStatus = batteryStatus(75, 7_500, 0, false, false);
        mockCapacitySnapshot(currentStatus);
        when(batteryManagementService.getCurrentBatteryStatus()).thenReturn(currentStatus);
        when(batteryManagementService.getBatteryStatusHistory()).thenReturn(List.of());
        when(seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge()).thenReturn(70);

        BatteryStatusMetrics metrics = service.calculateCurrentMetrics();

        assertEquals(0.0, metrics.dropRatePerHour(), 0.0001);
        assertNull(metrics.estimatedTimeToTargetHours());
    }

    private static BatteryStatusResponse batteryStatus(int rsoc,
                                                       int remainingCapacityWh,
                                                       int pacTotalW,
                                                       boolean charging,
                                                       boolean discharging) {
        BatteryStatusResponse response = new BatteryStatusResponse();
        response.setRsoc(rsoc);
        response.setRemainingCapacityWh(remainingCapacityWh);
        response.setPacTotalW(pacTotalW);
        response.setBatteryCharging(charging);
        response.setBatteryDischarging(discharging);
        return response;
    }

    private void mockCapacitySnapshot(BatteryStatusResponse status) {
        when(batteryCapacityService.calculateSnapshot(status)).thenReturn(new BatteryCapacitySnapshot(
                status.getRsoc(),
                status.getRemainingCapacityWh(),
                10_000,
                2,
                5_000,
                status.getRemainingCapacityWh()
        ));
    }
}
