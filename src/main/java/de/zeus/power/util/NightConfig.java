package de.zeus.power.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Copyright 2024 Guido Zeuner - https://tiny-tool.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
