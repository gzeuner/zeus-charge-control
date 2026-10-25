package de.zeus.power.ui;

import de.zeus.power.config.*;
import de.zeus.power.controller.*;
import de.zeus.power.entity.*;
import de.zeus.power.model.*;
import de.zeus.power.service.*;
import de.zeus.power.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MVC/Thymeleaf/JSON/persistence, simulated hardware. Never included in the application JAR. */
@Configuration(proxyBeanMethods = false)
@TestComponent
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = "de.zeus.power")
@EnableConfigurationProperties({BatteryProperties.class, PriceDisplayProperties.class})
@Import({ChargingStatusController.class, LicenseController.class, WebConfig.class,
        PriceDisplayService.class, BatteryCapacityService.class, TestEndpoints.class, SampleDataAdvice.class})
public class UiTestApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(UiTestApplication.class);
        app.setDefaultProperties(Map.of("server.address", "127.0.0.1", "server.port", "18080",
                "spring.datasource.url", "jdbc:h2:mem:ui-fixture", "spring.jpa.hibernate.ddl-auto", "create-drop",
                "spring.h2.console.enabled", "false", "logging.file.name", "target/ui-test-server.log"));
        app.run(args);
    }

    @Bean TestState testState() { return new TestState(); }
    @Bean FactoryBean<BatteryCommandService> batteryCommandService() { return isolated(mock(BatteryCommandService.class), BatteryCommandService.class); }

    @Bean FactoryBean<BatteryManagementService> batteryManagementService(TestState state) {
        BatteryManagementService service = mock(BatteryManagementService.class);
        when(service.getCurrentBatteryStatus()).thenAnswer(call -> state.available ? state.battery() : null);
        when(service.isBatteryNotConfigured()).thenAnswer(call -> state.notConfigured);
        when(service.isManualIdleActive()).thenAnswer(call -> state.paused);
        when(service.isForcedChargingActive()).thenAnswer(call -> state.forced);
        when(service.isNightIdleActive()).thenAnswer(call -> state.nightActive);
        when(service.getLastSetpointW()).thenAnswer(call -> state.forced ? 4600 : state.paused ? 1 : 0);
        when(service.initCharging(anyBoolean())).thenAnswer(call -> { if (state.reject) return false; state.forced = true; state.paused = false; return true; });
        when(service.resetToAutomaticMode(anyBoolean())).thenAnswer(call -> { if (state.reject) return false; state.forced = false; state.paused = false; return true; });
        when(service.pauseWithTinySetpoint()).thenAnswer(call -> !state.reject);
        doAnswer(call -> { state.paused = call.getArgument(0); return null; }).when(service).setManualIdleActive(anyBoolean());
        doAnswer(call -> { state.forced = call.getArgument(0); return null; }).when(service).setForcedChargingActive(anyBoolean());
        doAnswer(call -> { state.nightActive = call.getArgument(0); return null; }).when(service).setNightIdleActive(anyBoolean());
        return isolated(service, BatteryManagementService.class);
    }
    @Bean FactoryBean<ChargingUtils> chargingUtils(TestState state) {
        ChargingUtils utils = mock(ChargingUtils.class);
        when(utils.isNightChargingIdle()).thenAnswer(call -> state.nightEnabled);
        doAnswer(call -> { state.nightEnabled = call.getArgument(0); return null; }).when(utils).setNightChargingIdle(anyBoolean());
        return isolated(utils, ChargingUtils.class);
    }
    @Bean FactoryBean<ChargingManagementService> chargingManagementService(TestState state) {
        ChargingManagementService service = mock(ChargingManagementService.class);
        when(service.getSortedChargingSchedules()).thenAnswer(call -> state.empty ? List.of() : state.schedules());
        return isolated(service, ChargingManagementService.class);
    }
    @Bean FactoryBean<MarketPriceService> marketPriceService(TestState state) {
        MarketPriceService service = mock(MarketPriceService.class);
        when(service.getAllMarketPrices()).thenAnswer(call -> state.empty ? List.of() : state.prices());
        when(service.getCurrentlyValidPrice()).thenAnswer(call -> state.empty ? null : 8.42);
        return isolated(service, MarketPriceService.class);
    }
    @Bean FactoryBean<SeasonalTargetStateOfChargeService> seasonalTargetStateOfChargeService() {
        SeasonalTargetStateOfChargeService service = mock(SeasonalTargetStateOfChargeService.class);
        when(service.getCurrentTargetStateOfCharge()).thenReturn(70);
        when(service.getCurrentSeasonMessageKey()).thenReturn("seasonSummer");
        return isolated(service, SeasonalTargetStateOfChargeService.class);
    }
    @Bean FactoryBean<BatteryStatusMetricsService> batteryStatusMetricsService(TestState state) {
        BatteryStatusMetricsService service = mock(BatteryStatusMetricsService.class);
        when(service.calculateCurrentMetrics()).thenAnswer(call -> new BatteryStatusMetrics(null, state.available ? 0.29 : null));
        return isolated(service, BatteryStatusMetricsService.class);
    }

    // Factory products skip field population and @PostConstruct on mocked service classes.
    private static <T> FactoryBean<T> isolated(T value, Class<T> type) {
        return new FactoryBean<>() {
            public T getObject() { return value; }
            public Class<?> getObjectType() { return type; }
            public boolean isSingleton() { return true; }
        };
    }

    public static class TestState {
        public volatile boolean available = true, notConfigured, paused, forced, nightEnabled, nightActive, reject, empty;
        public TestState() { reset(); }
        public void reset() { available = true; notConfigured = paused = forced = nightEnabled = nightActive = reject = empty = false; NightConfig.updateNightHours(19, 6); }
        BatteryStatusResponse battery() {
            BatteryStatusResponse status = new BatteryStatusResponse();
            status.setRsoc(64); status.setRemainingCapacityWh(6400);
            status.setBatteryCharging(!paused); status.setBatteryDischarging(false);
            status.setPacTotalW(paused ? 1 : forced ? 4600 : 2040);
            status.setProductionW(3200); status.setConsumptionW(1160);
            status.setOperatingMode("2"); status.setSystemStatus("OnGrid");
            return status;
        }
        public List<MarketPrice> prices() {
            long base = Instant.now().truncatedTo(ChronoUnit.HOURS).toEpochMilli();
            double[] curve = { 10.7, 10.1, 9.2, 8.7, 8.42, 7.3, 6.8, 5.6, 4.8, 4.25, 5.1, 6.3, 8.4, 11.7, 13.8, 15.4, 16.2, 14.8, 12.6, 10.2, 8.5, 7.2, 6.1, 5.8, 6.5, 8.1, 10.5, 12.4, 11.8, 9.7 };
            List<MarketPrice> result = new ArrayList<>();
            for (int i = 0; i < curve.length; i++) {
                MarketPrice price = new MarketPrice(); price.setId((long) i + 1);
                price.setStartTimestamp(base + (i - 4) * 3600000L); price.setEndTimestamp(base + (i - 3) * 3600000L);
                price.setMarketPrice(i == 0 ? -10.0 : curve[i]); price.setUnit("cent/kWh"); result.add(price);
            }
            return result;
        }
        public List<ChargingSchedule> schedules() {
            return prices().stream().filter(price -> price.getMarketPrice() >= 0 && price.getMarketPrice() <= 5.1)
                    .limit(3).map(price -> { ChargingSchedule schedule = new ChargingSchedule();
                        schedule.setStartTimestamp(price.getStartTimestamp()); schedule.setEndTimestamp(price.getEndTimestamp());
                        schedule.setPrice(price.getMarketPrice()); return schedule; }).toList();
        }
    }
}

@RestController
@TestComponent
class TestEndpoints {
    private final UiTestApplication.TestState state;
    TestEndpoints(UiTestApplication.TestState state) { this.state = state; }
    @PostMapping("/__test/reset") Map<String, Boolean> reset() { state.reset(); return Map.of("ok", true); }
    @PostMapping("/__test/scenario") Map<String, Boolean> scenario(@RequestParam String name) {
        switch (name) {
            case "unavailable" -> state.available = false;
            case "unconfigured" -> { state.available = false; state.notConfigured = true; }
            case "reject" -> state.reject = true;
            case "empty" -> state.empty = true;
            case "recover" -> { state.available = true; state.notConfigured = false; state.reject = false; state.empty = false; }
            default -> throw new IllegalArgumentException("Unknown test scenario");
        }
        return Map.of("ok", true);
    }
}

@ControllerAdvice
@TestComponent
class SampleDataAdvice {
    @ModelAttribute("sampleData") boolean sampleData() { return true; }
}
