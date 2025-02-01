package de.zeus.power.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class NightConfig {

    @Value("${night.start:22}")
    private int nightStartHourConfig;

    @Value("${night.end:6}")
    private int nightEndHourConfig;

    private static int nightStartHour;
    private static int nightEndHour;

    @PostConstruct
    public void init() {
        nightStartHour = nightStartHourConfig;
        nightEndHour = nightEndHourConfig;
    }

    public static int getNightStartHour() {
        return nightStartHour;
    }

    public static int getNightEndHour() {
        return nightEndHour;
    }
}
