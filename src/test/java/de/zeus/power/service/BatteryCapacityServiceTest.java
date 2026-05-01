package de.zeus.power.service;

import de.zeus.power.config.BatteryProperties;
import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryCapacitySnapshot;
import de.zeus.power.model.BatteryStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatteryCapacityServiceTest {

    private BatteryCommandService batteryCommandService;

    @BeforeEach
    void setUp() {
        batteryCommandService = mock(BatteryCommandService.class);
    }

    @Test
    void calculateSnapshot_usesConfiguredModuleCapacityToKeepPercentAndWhConsistent() {
        BatteryCapacityService service = newService(configuredPropertiesWithApiKey(""));
        BatteryStatusResponse status = new BatteryStatusResponse();
        status.setRsoc(50);
        status.setRemainingCapacityWh(20_000);

        BatteryCapacitySnapshot snapshot = service.calculateSnapshot(status);

        assertEquals(50, snapshot.stateOfChargePercent());
        assertEquals(10_000, snapshot.totalCapacityWh());
        assertEquals(5_000, snapshot.remainingCapacityWh());
        assertEquals(20_000, snapshot.rawRemainingCapacityWh());
    }

    @Test
    void resolveInstalledModules_prefersApiWhenConfiguredAndAvailable() {
        BatteryCapacityService service = newService(configuredPropertiesWithApiKey("BatteryModules"));
        when(batteryCommandService.isBatteryNotConfigured()).thenReturn(false);
        when(batteryCommandService.getConfiguration("BatteryModules"))
                .thenReturn(new ApiResponse<>(true, HttpStatus.OK, "ok", "\"4\""));

        int installedModules = service.resolveInstalledModules();

        assertEquals(4, installedModules);
        verify(batteryCommandService).getConfiguration("BatteryModules");
    }

    @Test
    void resolveInstalledModules_fallsBackToPropertyWhenApiMissing() {
        BatteryCapacityService service = newService(configuredPropertiesWithApiKey("BatteryModules"));
        when(batteryCommandService.isBatteryNotConfigured()).thenReturn(false);
        when(batteryCommandService.getConfiguration("BatteryModules"))
                .thenReturn(new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "fail", null));

        int installedModules = service.resolveInstalledModules();

        assertEquals(2, installedModules);
    }

    private static BatteryProperties configuredPropertiesWithApiKey(String apiKey) {
        BatteryProperties batteryProperties = new BatteryProperties();
        batteryProperties.getCapacity().setInstalledModules(2);
        batteryProperties.getCapacity().setModuleUsableCapacityWh(5000);
        batteryProperties.getCapacity().setApiInstalledModulesConfigKey(apiKey);
        return batteryProperties;
    }

    private BatteryCapacityService newService(BatteryProperties batteryProperties) {
        BatteryCapacityService service = new BatteryCapacityService(batteryProperties, batteryCommandService);
        ReflectionTestUtils.setField(service, "configCacheDurationSeconds", 300);
        return service;
    }
}
