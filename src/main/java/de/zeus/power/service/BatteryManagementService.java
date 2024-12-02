package de.zeus.power.service;

import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryStatusResponse;
import de.zeus.power.repository.ChargingScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service for managing battery operations, such as charging and mode switching.
 * This class interacts with the battery system to ensure efficient and optimized operation.
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
 *
 * © 2024 - Guido Zeuner - https://tiny-tool.de
 */
@Service
public class BatteryManagementService {

    private static final Logger logger = LoggerFactory.getLogger(BatteryManagementService.class);

    public static final String OPERATING_MODE = "EM_OperatingMode";
    public static final String OP_MODE_MANUAL = "1";
    public static final String OP_MODE_AUTOMATIC = "2";

    private final BatteryCommandService commandService;
    private final OpenMeteoService weatherService;
    private final boolean batteryNotConfigured;

    @Value("${battery.target.stateOfCharge}")
    private int targetStateOfChargeInPercent;

    @Value("${battery.chargingPoint}")
    private int chargingPointInWatt;

    @Value("${battery.reduced.charge.factor:0.5}")
    private double reducedChargeFactor;

    @Value("${battery.status.cache.duration.seconds:60}")
    private int cacheDurationInSeconds;

    @Value("${battery.history.max.entries:10}")
    private int maxHistorySize;

    @Value("${battery.large.consumer.threshold:0.5}")
    private double largeConsumerThreshold;

    @Value("${weather.api.cloudcover.threshold:40}")
    private double cloudCoverThreshold;

    private BatteryStatusResponse cachedBatteryStatus;
    private Instant cacheTimestamp;
    private volatile boolean isReducedChargingActive = false;
    private ChargingScheduleRepository chargingScheduleRepository;


    private final Queue<Map.Entry<Long, Integer>> rsocHistory = new LinkedList<>();

    /**
     * Constructor for BatteryManagementService.
     *
     * @param commandService The service to interact with the battery commands.
     */
    public BatteryManagementService(BatteryCommandService commandService, OpenMeteoService weatherService, ChargingScheduleRepository chargingScheduleRepositor) {
        this.commandService = commandService;
        this.batteryNotConfigured = commandService.isBatteryNotConfigured();
        this.weatherService = weatherService;
        this.chargingScheduleRepository = chargingScheduleRepositor;
    }

    @Scheduled(fixedRateString = "${large.consumer.check.interval:300000}") // Every 5 minutes (default)
    public void scheduledLargeConsumerCheck() {
        isLargeConsumerActive();
    }

    public boolean isLargeConsumerActive() {
        int currentRSOC = getRelativeStateOfCharge();
        long currentTime = System.currentTimeMillis();

        // Add the current RSOC value with a timestamp to the history
        rsocHistory.add(new AbstractMap.SimpleEntry<>(currentTime, currentRSOC));

        // Limit the size of the history to `maxHistorySize`
        while (rsocHistory.size() > maxHistorySize) {
            rsocHistory.poll();
        }

        // If not enough data points exist, a large consumer cannot be determined
        if (rsocHistory.size() < 2) {
            logger.debug("Not enough RSOC data points to determine large consumer activity.");
            return false;
        }

        // Analyze RSOC data
        Map.Entry<Long, Integer> oldest = rsocHistory.peek();
        double timeDifferenceInMinutes = (currentTime - oldest.getKey()) / 60000.0;
        double rsocDifference = oldest.getValue() - currentRSOC;

        // Calculate the RSOC drop per minute
        double rsocDropPerMinute = rsocDifference / timeDifferenceInMinutes;
        logger.debug("RSOC drop per minute: {}% (threshold: {}%)", rsocDropPerMinute, largeConsumerThreshold);

        // Determine large consumer activity
        boolean largeConsumerDetected = rsocDropPerMinute >= largeConsumerThreshold;
        if (largeConsumerDetected) {
            logger.info("Large consumer detected. RSOC drop rate: {}%/min exceeds threshold: {}%.", rsocDropPerMinute, largeConsumerThreshold);
        }
        return largeConsumerDetected;
    }
    /**
     * Sets a reduced charging point based on the configured reduction factor.
     *
     * @return True if the reduced charging point was successfully set, false otherwise.
     */
    public boolean setReducedChargePoint() {
        if (isBatteryNotConfigured()) {
            logger.warn("Battery not configured. Cannot set reduced charge point.");
            return false;
        }

        int reducedChargePoint = (int) (getChargingPointInWatt() * reducedChargeFactor);

        ApiResponse<?> response = commandService.setChargePoint(reducedChargePoint);
        if (response.success()) {
            isReducedChargingActive = true;
            logger.info("Reduced charging point successfully set to {} Watt using factor {}.", reducedChargePoint, reducedChargeFactor);
            return true;
        } else {
            logger.error("Failed to set reduced charge point to {} Watt using factor {}. Response: {}", reducedChargePoint, reducedChargeFactor, response.message());
            return false;
        }
    }
    /**
     * Dynamically sets the charging point in Watt.
     *
     * @param currentChargingPoint The desired charging point value in Watt.
     * @return True if the charging point was successfully set, false otherwise.
     */
    public boolean setDynamicChargingPoint(int currentChargingPoint) {
        if (isBatteryNotConfigured()) {
            logger.warn("Battery not configured. Cannot set charging point.");
            return false;
        }

        if (currentChargingPoint < 0) {
            logger.warn("Invalid charging point value: {}. Charging point must be 0 or greater.", currentChargingPoint);
            return false;
        }

        ApiResponse<?> response = commandService.setChargePoint(currentChargingPoint);
        if (response.success()) {
            logger.info("Charging point successfully set to {} Watt.", currentChargingPoint);
            isReducedChargingActive = currentChargingPoint == chargingPointInWatt;
            return true;
        } else {
            logger.error("Failed to set charging point to {} Watt. Response: {}", currentChargingPoint, response.message());
            return false;
        }
    }


    public boolean initCharging(boolean forceCharging) {
        // Invalidate cached battery status
        invalidateBatteryCache();

        // Prüfe Grundvoraussetzungen
        if (!checkPreconditions()) {
            return false;
        }

        long currentTime = System.currentTimeMillis();

        // Für geplantes Laden: Sicherstellen, dass die aktuelle Zeit in einer geplanten Periode liegt
        Optional<ChargingSchedule> activeSchedule = chargingScheduleRepository.findAll().stream()
                .filter(schedule -> currentTime >= schedule.getStartTimestamp() && currentTime < schedule.getEndTimestamp())
                .findFirst();

        if (activeSchedule.isEmpty()) {
            if (forceCharging) {
                // Wenn Forced Mode aktiv ist, aber keine aktuelle Periode existiert, abbrechen
                logger.error("No active charging schedule for the current time. Forced charging cannot be initiated for future periods.");
                return false;
            }
            logger.info("No active charging schedule for the current time. Skipping charging.");
            return false;
        }

        // Überprüfen, ob der Ladezustand das Ziel erreicht hat
        int relativeStateOfCharge = getRelativeStateOfCharge();
        if (relativeStateOfCharge >= targetStateOfChargeInPercent) {
            logger.info("Charging skipped: Battery charge level ({}) is at or above the target ({}%).",
                    relativeStateOfCharge, targetStateOfChargeInPercent);
            return false;
        }

        // Prüfen, ob bereits geladen wird und ob dies erlaubt ist
        if (!isBatteryChargingAllowed(forceCharging)) {
            return false;
        }

        // Prüfen, ob Solarstrom aktiv ist und Netzladung verhindert werden sollte
        if (isBatteryCharging() && !forceCharging) {
            logger.info("Solar charging already active. Preventing additional grid charging.");
            return false;
        }

        // Wetterdaten prüfen, falls verfügbar
        Optional<Double> cloudCover = weatherService.getCurrentCloudCover();
        if (cloudCover.isPresent()) {
            double currentCloudCover = cloudCover.get();
            if (currentCloudCover >= cloudCoverThreshold) {
                logger.info("High cloud cover detected ({}%). Adjusting charging power.", currentCloudCover);
                setReducedChargePoint();
            } else {
                logger.info("Low cloud cover detected ({}%). Optimal solar conditions.", currentCloudCover);
            }
        } else {
            logger.warn("No weather data available. Proceeding with default charging configuration.");
        }

        // Aktivieren des manuellen Ladebetriebs
        ApiResponse<?> manualModeResponse = activateManualOperatingMode();
        if (!manualModeResponse.success()) {
            logger.error("Failed to activate manual operating mode for charging.");
            return false;
        }

        // Ladepunkt setzen
        ApiResponse<?> chargePointResponse = commandService.setChargePoint(chargingPointInWatt);
        if (!chargePointResponse.success()) {
            logger.error("Failed to set charge point to {} Watt.", chargingPointInWatt);
            return false;
        }

        isReducedChargingActive = false; // Rücksetzen der reduzierten Ladung
        logger.info("Charging initiated successfully at {} Watt in {} mode.", chargingPointInWatt, forceCharging ? "Forced" : "Planned");
        return true;
    }


    private boolean isBatteryChargingAllowed(boolean forceCharging) {
        if (!forceCharging && isBatteryCharging()) {
            logger.info("Battery is already charging. Current RSOC: {}%, Target RSOC: {}%.",
                    getRelativeStateOfCharge(), targetStateOfChargeInPercent);
            return false;
        }
        return true;
    }

    private boolean checkPreconditions() {
        if (isBatteryNotConfigured()) {
            logger.info("Battery not configured. Skipping charging.");
            return false;
        }
        return true;
    }

    public boolean resetToAutomaticMode() {

        invalidateBatteryCache();

        if (isBatteryNotConfigured()) {
            return false;
        }

        if (!isManualOperatingMode()) {
            logger.info("Battery is not in manual mode, no need to switch to automatic mode.");
            return true;
        }

        // Activate automatic mode
        ApiResponse<?> automaticModeResponse = activateAutomaticOperatingMode();
        if (!automaticModeResponse.success()) {
            logger.info("Failed to activate automatic operating mode.");
            return false;
        }

        logger.info("Successfully returned to automatic operating mode and reset ChargingPoint to {} Watt.", chargingPointInWatt);
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
            logger.info("Failed to obtain current battery status; assuming RSOC is 0%.");
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

    public ApiResponse<?> activateManualOperatingMode() {
        invalidateBatteryCache();
        return commandService.setConfiguration(OPERATING_MODE, OP_MODE_MANUAL);
    }

    public ApiResponse<?> activateAutomaticOperatingMode() {
        invalidateBatteryCache();
        return commandService.setConfiguration(OPERATING_MODE, OP_MODE_AUTOMATIC);
    }

    public int getChargingPointInWatt() {
        return chargingPointInWatt;
    }

    public boolean isReducedChargingCurrentlyActive() {
        return isReducedChargingActive;
    }

    public BatteryStatusResponse getCurrentBatteryStatus() {
        if (isBatteryNotConfigured()) {
            return null;
        }

        if (cachedBatteryStatus != null && cacheTimestamp != null) {
            long secondsSinceLastUpdate = ChronoUnit.SECONDS.between(cacheTimestamp, Instant.now());
            if (secondsSinceLastUpdate <= cacheDurationInSeconds) {
                logger.debug("Returning cached battery status ({} seconds old)", secondsSinceLastUpdate);
                return cachedBatteryStatus;
            }
        }

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
