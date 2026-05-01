package de.zeus.power.service;

import de.zeus.power.config.BatteryProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeasonalTargetStateOfChargeServiceTest {

    @Test
    void returnsWinterTargetAcrossYearBoundary() {
        SeasonalTargetStateOfChargeService service = new SeasonalTargetStateOfChargeService(new BatteryProperties());

        assertEquals(90, service.getTargetStateOfCharge(LocalDate.of(2026, 11, 15)));
        assertEquals(90, service.getTargetStateOfCharge(LocalDate.of(2026, 1, 15)));
        assertEquals(SeasonalTargetStateOfChargeService.SeasonMode.WINTER, service.getSeasonMode(LocalDate.of(2026, 2, 1)));
    }

    @Test
    void returnsSummerTargetForConfiguredSummerMonths() {
        SeasonalTargetStateOfChargeService service = new SeasonalTargetStateOfChargeService(new BatteryProperties());

        assertEquals(70, service.getTargetStateOfCharge(LocalDate.of(2026, 3, 1)));
        assertEquals(70, service.getTargetStateOfCharge(LocalDate.of(2026, 8, 10)));
        assertEquals(SeasonalTargetStateOfChargeService.SeasonMode.SUMMER, service.getSeasonMode(LocalDate.of(2026, 10, 31)));
    }

    @Test
    void honorsCustomizedSeasonPropertiesAndCurrentClock() {
        BatteryProperties properties = new BatteryProperties();
        properties.getSeason().getWinter().setStartMonth(10);
        properties.getSeason().getWinter().setEndMonth(1);
        properties.getSeason().getWinter().setTargetStateOfCharge(95);
        properties.getSeason().getSummer().setStartMonth(2);
        properties.getSeason().getSummer().setEndMonth(9);
        properties.getSeason().getSummer().setTargetStateOfCharge(65);

        Clock julyClock = Clock.fixed(Instant.parse("2026-07-03T08:00:00Z"), ZoneId.of("Europe/Berlin"));
        SeasonalTargetStateOfChargeService service = new SeasonalTargetStateOfChargeService(properties, julyClock);

        assertEquals(65, service.getCurrentTargetStateOfCharge());
        assertEquals(95, service.getTargetStateOfCharge(LocalDate.of(2026, 12, 24)));
        assertEquals(SeasonalTargetStateOfChargeService.SeasonMode.SUMMER, service.getCurrentSeasonMode());
    }
}
