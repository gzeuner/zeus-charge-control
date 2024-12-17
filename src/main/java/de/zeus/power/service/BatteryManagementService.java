package de.zeus.power.service;

import de.zeus.power.config.LogFilter;
import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryStatusResponse;
import de.zeus.power.repository.ChargingScheduleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
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

    public static final String OPERATING_MODE = "EM_OperatingMode";
    public static final String OP_MODE_MANUAL = "1";
    public static final String OP_MODE_AUTOMATIC = "2";

    private final BatteryCommandService commandService;
    private final OpenMeteoService weatherService;
    private final boolean batteryNotConfigured;
    private boolean forcedChargingActive = false;

    @Value("${battery.target.stateOfCharge}")
    private int targetStateOfChargeInPercent;

    @Value("${battery.chargingPoint}")
    private int chargingPointInWatt;

    private int currentChargingPoint;

    @Value("${battery.status.cache.duration.seconds:60}")
    private int cacheDurationInSeconds;

    @Value("${battery.history.max.entries:10}")
    private int maxHistorySize;

    @Value("${battery.large.consumer.threshold:0.5}")
    private double largeConsumerThreshold;

    @Value("${night.start:22}")
    private int nightStartHour;

    @Value("${night.end:6}")
    private int nightEndHour;

    @Value("${battery.target.stateOfCharge:90}")
    private int targetStateOfCharge;

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

        // Check if the history has at least one entry
        if (rsocHistory.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "RSOC history is empty. Cannot determine large consumer activity.");
            return false;
        }

        // If not enough data points exist, a large consumer cannot be determined
        if (rsocHistory.size() < 2) {
            LogFilter.log(LogFilter.LOG_LEVEL_DEBUG, "Not enough RSOC data points to determine large consumer activity.");
            return false;
        }

        // Analyze RSOC data
        Map.Entry<Long, Integer> oldest = rsocHistory.peek();
        if (oldest == null) {
            LogFilter.log(LogFilter.LOG_LEVEL_ERROR, "Failed to retrieve the oldest RSOC entry from history.");
            return false;
        }

        double timeDifferenceInMinutes = (currentTime - oldest.getKey()) / 60000.0;
        double rsocDifference = oldest.getValue() - currentRSOC;

        // Calculate the RSOC drop per minute
        double rsocDropPerMinute = rsocDifference / timeDifferenceInMinutes;
        LogFilter.log(LogFilter.LOG_LEVEL_DEBUG,
                String.format("RSOC drop per minute: %.2f%% (threshold: %.2f%%)", rsocDropPerMinute, largeConsumerThreshold));

        // Determine large consumer activity
        boolean largeConsumerDetected = rsocDropPerMinute >= largeConsumerThreshold;
        if (largeConsumerDetected) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                    String.format("Large consumer detected. RSOC drop rate: %.2f%%/min exceeds threshold: %.2f%%.", rsocDropPerMinute, largeConsumerThreshold));
        }
        return largeConsumerDetected;
    }

    /**
     * Dynamically sets the charging point in Watt.
     *
     * @param currentChargingPoint The desired charging point value in Watt.
     * @return True if the charging point was successfully set, false otherwise.
     */
    public boolean setDynamicChargingPoint(int currentChargingPoint) {
        if (isBatteryNotConfigured()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "Battery not configured. Cannot set charging point.");
            return false;
        }

        if (currentChargingPoint < 0) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN,
                    String.format("Invalid charging point value: %d. Charging point must be 0 or greater.", currentChargingPoint));
            return false;
        }

        ApiResponse<?> response = commandService.setChargePoint(currentChargingPoint);
        if (response.success()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                    String.format("Charging point successfully set to %d Watt.", currentChargingPoint));
            isReducedChargingActive = currentChargingPoint == chargingPointInWatt;
            return true;
        } else {
            LogFilter.log(LogFilter.LOG_LEVEL_ERROR,
                    String.format("Failed to set charging point to %d Watt. Response: %s", currentChargingPoint, response.message()));
            return false;
        }
    }

    /**
     * Dynamically adjusts the charging point based on current RSOC, nighttime, and large consumer activity.
     *
     * @param currentRSOC        Current relative state of charge (RSOC) in percentage.
     * @param isNightTime        Whether it is currently nighttime.
     * @param largeConsumerActive Whether a large consumer is active.
     * @return True if the charging point was successfully adjusted, false otherwise.
     */
    public boolean adjustChargingPointDynamically(int currentRSOC, boolean isNightTime, boolean largeConsumerActive) {
        if (isBatteryNotConfigured()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "Battery not configured. Cannot adjust charging point.");
            return false;
        }

        if (isNightTime && largeConsumerActive) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                    "Nighttime and large consumer detected. Adjusting charging point dynamically.");

            // Dynamically reduce charging point based on RSOC
            int reducedChargingPoint = currentRSOC < 50
                    ? (int) (chargingPointInWatt * 0.5) // Charge faster if RSOC < 50%
                    : (int) (chargingPointInWatt * 0.3); // Otherwise, charge at 30%

            boolean success = setDynamicChargingPoint(reducedChargingPoint);
            if (success) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                        String.format("Charging point adjusted to %d Watt for nighttime with large consumer.", reducedChargingPoint));
            }
            return success;
        }

        if (currentRSOC >= targetStateOfCharge && isManualOperatingMode()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                    "RSOC at or above target. Setting charging point to 0 for idle mode.");

            return setDynamicChargingPoint(0); // Set to idle mode if target state is reached
        }

        // Default behavior for other cases
        LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                "No special conditions met. Retaining current charging point.");
        return true;
    }


    public boolean initCharging(boolean forceCharging) {
        // Update the forced charging status
        this.forcedChargingActive = forceCharging;

        // Invalidate cached battery status
        invalidateBatteryCache();

        // Check basic requirements
        if (!checkPreconditions()) {
            this.forcedChargingActive = false; // Reset flag on failure
            return false;
        }

        long currentTime = System.currentTimeMillis();

        // Ensure that the current time is within a scheduled period
        Optional<ChargingSchedule> activeSchedule = chargingScheduleRepository.findAll().stream()
                .filter(schedule -> currentTime >= schedule.getStartTimestamp() && currentTime < schedule.getEndTimestamp())
                .findFirst();

        if (activeSchedule.isEmpty() && !forceCharging) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "No active charging schedule for the current time. Skipping charging.");
            this.forcedChargingActive = false; // Reset flag if no active schedule and not forced
            return false;
        }

        // Check whether the charge level has reached the target
        int relativeStateOfCharge = getRelativeStateOfCharge();
        if (relativeStateOfCharge >= targetStateOfChargeInPercent) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                    String.format("Charging skipped: Battery charge level (%d%%) is at or above the target (%d%%).",
                            relativeStateOfCharge, targetStateOfChargeInPercent));
            this.forcedChargingActive = false; // Reset flag if target is reached
            return false;
        }

        // Check whether loading is already in progress and whether this is permitted
        if (!isBatteryChargingAllowed(forceCharging)) {
            this.forcedChargingActive = false; // Reset flag if charging not allowed
            return false;
        }

        // Check solar activity
        if (isBatteryCharging() && !forceCharging) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Solar charging already active. Preventing additional grid charging.");
            return false;
        }

        // Adjust the charging point based on weather data
        adjustChargingPointBasedOnWeather();

        // Activate manual charging mode
        ApiResponse<?> manualModeResponse = activateManualOperatingMode();
        if (!manualModeResponse.success()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Failed to activate manual operating mode for charging.");
            this.forcedChargingActive = false; // Reset flag on failure
            return false;
        }

        // Set charging point dynamically
        boolean success = setDynamicChargingPoint(currentChargingPoint);
        if (!success) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                    String.format("Failed to set charge point to %d Watt.", currentChargingPoint));
            this.forcedChargingActive = false; // Reset flag on failure
            return false;
        }

        isReducedChargingActive = false;
        LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                String.format("Charging initiated successfully at %d Watt in %s mode.", currentChargingPoint, forceCharging ? "Forced" : "Planned"));
        return true;
    }


    public boolean isBatteryChargingAllowed(boolean forceCharging) {
        if (!forceCharging && isBatteryCharging()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                    String.format("Battery is already charging. Current RSOC: %d%%, Target RSOC: %d%%.",
                            getRelativeStateOfCharge(), targetStateOfChargeInPercent));
            return false;
        }
        return true;
    }

    private boolean checkPreconditions() {
        if (isBatteryNotConfigured()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO , "Battery not configured. Skipping charging.");
            return false;
        }
        return true;
    }

    public boolean resetToAutomaticMode() {

        invalidateBatteryCache();

        if(isAutomaticOperatingMode()) {
            LogFilter.log(LogFilter.LOG_LEVEL_DEBUG, "Battery is not in manual mode, no need to switch to automatic mode.");
            return true;
        }

        if (isBatteryNotConfigured()) {
            return false;
        }

        // Activate automatic mode
        ApiResponse<?> automaticModeResponse = activateAutomaticOperatingMode();
        if (!automaticModeResponse.success()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Failed to activate automatic operating mode.");
            return false;
        }

        // Reset the forced charging flag
        setForcedChargingActive(false);

        LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                String.format("Successfully returned to automatic operating mode and reset ChargingPoint to %d Watt.", chargingPointInWatt));
        return true;
    }

    public boolean isForcedChargingActive() {
        return forcedChargingActive;
    }

    public void setForcedChargingActive(boolean forcedChargingActive) {
        this.forcedChargingActive = forcedChargingActive;
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
            LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                    String.format("Current relative state of charge (RSOC) is: %d%%", rsoc));
            return rsoc;
        } else {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO,"Failed to obtain current battery status; assuming RSOC is 0%.");
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

    public BatteryStatusResponse getCurrentBatteryStatus() {
        if (isBatteryNotConfigured()) {
            return null;
        }

        if (cachedBatteryStatus != null && cacheTimestamp != null) {
            long secondsSinceLastUpdate = ChronoUnit.SECONDS.between(cacheTimestamp, Instant.now());
            if (secondsSinceLastUpdate <= cacheDurationInSeconds) {
                LogFilter.log(LogFilter.LOG_LEVEL_DEBUG,
                        String.format("Returning cached battery status (%d seconds old)", secondsSinceLastUpdate));
                return cachedBatteryStatus;
            }
        }

        ApiResponse<BatteryStatusResponse> response = commandService.getStatus();
        if (response != null && response.data() != null) {
            cachedBatteryStatus = response.data();
            cacheTimestamp = Instant.now();
            LogFilter.log("INFO", "Fetched new battery status");
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
        LogFilter.log(LogFilter.LOG_LEVEL_DEBUG, "Battery status cache invalidated.");
    }



    private void adjustChargingPointBasedOnWeather() {
        LocalTime now = LocalTime.now();

        if (isNightTime(now) && !isLargeConsumerActive()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Nighttime detected. Skipping weather-based charging point adjustment.");
            currentChargingPoint = chargingPointInWatt;
        }

        Optional<Double> optionalCloudCover = weatherService.getCurrentCloudCover();

        if (optionalCloudCover.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No cloud cover data available. Using default charging point.");
            currentChargingPoint = chargingPointInWatt;
            return;
        }

        double cloudCover = optionalCloudCover.get();
        LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                String.format("Current cloud cover: %.2f%%. Calculating charging point dynamically.", cloudCover));

        if (cloudCover < 30.0) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Cloud cover is below 30%. Skipping scheduled charging (solar power only).");
            currentChargingPoint = 0;
            return;
        }

        currentChargingPoint = (int) Math.round((cloudCover / 100.0) * chargingPointInWatt);
        LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                String.format("Dynamically adjusted charging point: %d Watt (based on %.2f%% cloud cover).",
                        currentChargingPoint, cloudCover));
    }


    private boolean isNightTime(LocalTime currentTime) {
        LocalTime nightStart = LocalTime.of(nightStartHour, 0);
        LocalTime nightEnd = LocalTime.of(nightEndHour, 0);

        if (nightStartHour > nightEndHour) {
            return currentTime.isAfter(nightStart) || currentTime.isBefore(nightEnd);
        } else {
            return currentTime.isAfter(nightStart) && currentTime.isBefore(nightEnd);
        }
    }

    public List<Map.Entry<Long, Integer>> getRsocHistory() {
        return new ArrayList<>(rsocHistory);
    }

}
