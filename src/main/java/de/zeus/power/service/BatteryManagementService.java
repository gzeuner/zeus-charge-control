package de.zeus.power.service;

import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Copyright 2024 Guido Zeuner
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * © 2024 - Guido Zeuner - https://tiny-tool.de
 *
 */

@Service
public class BatteryManagementService {

    /**
     * Logger instance for logging events in BatteryManagementService.
     */
    private static final Logger logger = LoggerFactory.getLogger(BatteryManagementService.class);

    public static final String OPERATING_MODE = "EM_OperatingMode";
    public static final String OP_MODE_MANUAL = "1";
    public static final String OP_MODE_AUTOMATIC = "2";
    private final BatteryCommandService commandService;
    private final boolean batteryNotConfigured;

    @Value("${battery.target.stateOfCharge}")
    private int targetStateOfChargeInPercent;

    @Value("${battery.chargingPoint}")
    private int chargingPointInWatt;

    @Value("${battery.status.cache.duration.seconds:60}")
    private int cacheDurationInSeconds;

    private BatteryStatusResponse cachedBatteryStatus;
    private Instant cacheTimestamp;

    /**
     * Autowired constructor for BatteryManagementService.
     *
     * @param commandService The BatteryCommandService instance to use for sending commands to the battery.
     */
    @Autowired
    public BatteryManagementService(BatteryCommandService commandService) {
        this.commandService = commandService;
        this.batteryNotConfigured = commandService.isBatteryNotConfigured();
    }

    public boolean initCharging(boolean forceCharging) {

        if (isBatteryNotConfigured()) {
            return false;
        }

        if (isBatteryCharging()) {
            if (!forceCharging) {
                logger.info("Charging initiation skipped: Battery is already charging.");
                return false;
            }
        }

        int relativeStateOfCharge = getRelativeStateOfCharge();
        if (relativeStateOfCharge > targetStateOfChargeInPercent) {
            if (!forceCharging) {
                logger.info("Charging initiation skipped: Battery charge level ({}) is above the threshold ({}%).",
                        relativeStateOfCharge, targetStateOfChargeInPercent);
                return false;
            }
        }

        ApiResponse<?> manualModeResponse = activateManualOperatingMode();
        if (!manualModeResponse.success()) {
            logger.info("Failed to activate manual operating mode for charging.");
            return false;
        }

        ApiResponse<?> chargePointResponse = commandService.setChargePoint(chargingPointInWatt);
        if (!chargePointResponse.success()) {
            logger.info("Failed to set charge point to {} Watt.", chargingPointInWatt);
            return false;
        }
        invalidateBatteryCache();
        logger.info("Charging initiated successfully at {} Watt.", chargingPointInWatt);
        return true;
    }


    public boolean resetToAutomaticMode() {

        if (isBatteryNotConfigured()) {
            return false;
        }

        // First check whether the battery is in manual operating mode
        if (!isManualOperatingMode()) {
            logger.info("Battery is not in manual mode, no need to switch to automatic mode.");
            return true;
        }

        // Attempts to switch to automatic mode when in manual mode
        ApiResponse<?> automaticModeResponse = activateAutomaticOperatingMode();
        if (!automaticModeResponse.success()) {
            logger.info("Failed to activate automatic operating mode.");
            return false;
        }
        invalidateBatteryCache();
        logger.info("Successfully returned to automatic operating mode.");
        return true;
    }

    public boolean isBatteryCharging() {

        if (isBatteryNotConfigured()) {
            return false;
        }

        BatteryStatusResponse batteryStatusResponse = getCurrentBatteryStatus();
        return batteryStatusResponse != null && batteryStatusResponse.isBatteryCharging();
    }

    public boolean isBatteryDischarging() {

        if (isBatteryNotConfigured()) {
            return false;
        }

        BatteryStatusResponse batteryStatusResponse = getCurrentBatteryStatus();
        return batteryStatusResponse != null && batteryStatusResponse.isBatteryDischarging();
    }

    public int getRelativeStateOfCharge() {

        if (isBatteryNotConfigured()) {
            return 0;
        }

        BatteryStatusResponse batteryStatusResponse = getCurrentBatteryStatus();
        if (batteryStatusResponse != null) {
            int rsoc = batteryStatusResponse.getRsoc();
            logger.info("Current relative state of charge (RSOC) is: {}%", rsoc);
            return rsoc;
        } else {
            logger.info("Failed to obtain current battery status; assuming RSOC is 0%");
            return 0;
        }
    }

    public int getRemainingCapacityWh() {

        if (isBatteryNotConfigured()) {
            return 0;
        }

        BatteryStatusResponse batteryStatusResponse = getCurrentBatteryStatus();
        return batteryStatusResponse != null ? batteryStatusResponse.getRemainingCapacityWh() : 0;
    }

    public boolean isManualOperatingMode() {

        if (isBatteryNotConfigured()) {
            return false;
        }

        BatteryStatusResponse batteryStatusResponse = getCurrentBatteryStatus();
        return batteryStatusResponse != null && OP_MODE_MANUAL.equals(batteryStatusResponse.getOperatingMode());
    }

    public boolean isAutomaticOperatingMode() {

        if (isBatteryNotConfigured()) {
            return false;
        }

        BatteryStatusResponse batteryStatusResponse = getCurrentBatteryStatus();
        return batteryStatusResponse != null && OP_MODE_AUTOMATIC.equals(batteryStatusResponse.getOperatingMode());
    }

    private ApiResponse<?> activateManualOperatingMode() {
        return commandService.setConfiguration(OPERATING_MODE, OP_MODE_MANUAL);
    }

    private ApiResponse<?> activateAutomaticOperatingMode() {
        return commandService.setConfiguration(OPERATING_MODE, OP_MODE_AUTOMATIC);
    }

    public BatteryStatusResponse getCurrentBatteryStatus() {

        if (isBatteryNotConfigured()) {
            return null;
        }

        // Check whether the cached data is still valid
        if (cachedBatteryStatus != null && cacheTimestamp != null) {
            long secondsSinceLastUpdate = ChronoUnit.SECONDS.between(cacheTimestamp, Instant.now());
            if (secondsSinceLastUpdate <= cacheDurationInSeconds) {
                logger.debug("Returning cached battery status ({} seconds old)", secondsSinceLastUpdate);
                return cachedBatteryStatus;
            }
        }

        // If the cached data is no longer valid, retrieve new data
        ApiResponse<BatteryStatusResponse> response = commandService.getStatus();
        if (response != null && response.data() != null) {
            cachedBatteryStatus = response.data();
            cacheTimestamp = Instant.now();
            logger.info("Fetched new battery status");
            return cachedBatteryStatus;
        } else {
            return null;
        }
    }

    public boolean isBatteryNotConfigured() {
        return batteryNotConfigured;
    }

    public void invalidateBatteryCache() {
        cachedBatteryStatus = null;
        cacheTimestamp = null;
        logger.debug("Battery status cache invalidated.");
    }

}
