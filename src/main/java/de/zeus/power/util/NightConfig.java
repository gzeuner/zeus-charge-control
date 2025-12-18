package de.zeus.power.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Copyright 2025 Guido Zeuner - https://tiny-tool.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 *
 * Holds night start/end hour configuration for reuse across services and utilities.
 */
@Component
public class NightConfig {

    /**
     * Holds night start/end hour configuration for reuse across services and utilities.
     * Author: User
     */
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

    public static synchronized void updateNightHours(int startHour, int endHour) {
        validate(startHour, endHour);
        nightStartHour = startHour;
        nightEndHour = endHour;
    }

    public static int getNightStartHour() {
        return nightStartHour;
    }

    public static int getNightEndHour() {
        return nightEndHour;
    }

    private static void validate(int start, int end) {
        if (start < 0 || start > 23 || end < 0 || end > 23) {
            throw new IllegalArgumentException("Invalid night hours: " + start + " to " + end);
        }
    }
}
