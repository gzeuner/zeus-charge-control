package de.zeus.power.service;

import de.zeus.power.config.BatteryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Resolves the currently active target RSOC from seasonal configuration.
 */
@Service
public class SeasonalTargetStateOfChargeService {

    public enum SeasonMode {
        WINTER("seasonWinter"),
        SUMMER("seasonSummer");

        private final String messageKey;

        SeasonMode(String messageKey) {
            this.messageKey = messageKey;
        }

        public String getMessageKey() {
            return messageKey;
        }
    }

    private final BatteryProperties batteryProperties;
    private final Clock clock;

    @Autowired
    public SeasonalTargetStateOfChargeService(BatteryProperties batteryProperties) {
        this(batteryProperties, Clock.systemDefaultZone());
    }

    SeasonalTargetStateOfChargeService(BatteryProperties batteryProperties, Clock clock) {
        this.batteryProperties = batteryProperties;
        this.clock = clock;
    }

    public int getCurrentTargetStateOfCharge() {
        return getTargetStateOfCharge(LocalDate.now(clock));
    }

    public int getTargetStateOfCharge(LocalDate date) {
        return getSeasonConfig(getSeasonMode(date)).getTargetStateOfCharge();
    }

    public SeasonMode getCurrentSeasonMode() {
        return getSeasonMode(LocalDate.now(clock));
    }

    public String getCurrentSeasonMessageKey() {
        return getCurrentSeasonMode().getMessageKey();
    }

    SeasonMode getSeasonMode(LocalDate date) {
        int month = date.getMonthValue();
        BatteryProperties.SeasonTargetProperties winter = batteryProperties.getSeason().getWinter();
        BatteryProperties.SeasonTargetProperties summer = batteryProperties.getSeason().getSummer();

        boolean inWinter = isMonthInRange(month, winter.getStartMonth(), winter.getEndMonth());
        boolean inSummer = isMonthInRange(month, summer.getStartMonth(), summer.getEndMonth());

        if (inWinter && !inSummer) return SeasonMode.WINTER;
        if (inSummer && !inWinter) return SeasonMode.SUMMER;
        if (inWinter) return SeasonMode.WINTER;
        return SeasonMode.SUMMER;
    }

    private BatteryProperties.SeasonTargetProperties getSeasonConfig(SeasonMode mode) {
        return mode == SeasonMode.WINTER
                ? batteryProperties.getSeason().getWinter()
                : batteryProperties.getSeason().getSummer();
    }

    private boolean isMonthInRange(int month, int startMonth, int endMonth) {
        validateMonth(month);
        validateMonth(startMonth);
        validateMonth(endMonth);

        if (startMonth <= endMonth) {
            return month >= startMonth && month <= endMonth;
        }
        return month >= startMonth || month <= endMonth;
    }

    private void validateMonth(int month) {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("Month must be between 1 and 12 but was " + month);
        }
    }
}
