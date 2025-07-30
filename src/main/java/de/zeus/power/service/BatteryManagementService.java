package de.zeus.power.service;

import de.zeus.power.config.LogFilter;
import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryStatusResponse;
import de.zeus.power.repository.ChargingScheduleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static de.zeus.power.util.ChargingUtils.isNight;

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

    // Constants for operating modes
    private static final String OPERATING_MODE = "EM_OperatingMode";
    private static final String OP_MODE_MANUAL = "1";
    private static final String OP_MODE_AUTOMATIC = "2";
    private static final String OP_MODE_TIME_OF_USE = "10";
    private static final String OP_MODE_AUTOMATIC_WITH_OPTIMIZATION = "11";

    // Constants for retry logic and default RSOC
    private static final int RETRY_DELAY_MS = 15_000; // Delay between retry attempts in milliseconds
    private static final int MAX_RETRIES = 4;         // Maximum number of retry attempts
    private static final int DEFAULT_RSOC = 15;       // Default RSOC value if retrieval fails

    // Dependencies injected via constructor
    private final BatteryCommandService commandService;
    private final OpenMeteoService weatherService;
    private final ChargingScheduleRepository chargingScheduleRepository;
    private final boolean batteryNotConfigured;
    private boolean manualIdleActive = false;

    // Configuration values injected via Spring
    @Value("${battery.target.stateOfCharge:90}")
    private int targetStateOfCharge;

    @Value("${battery.chargingPoint}")
    private int chargingPointInWatt;

    @Value("${battery.status.cache.duration.seconds:60}")
    private int cacheDurationInSeconds;

    @Value("${battery.history.max.entries:24}")
    private int maxHistorySize;

    // Instance variables for state management
    private BatteryStatusResponse cachedBatteryStatus;
    private Instant cacheTimestamp;
    private volatile boolean forcedChargingActive = false;
    private volatile boolean isReducedChargingActive = false;
    private int currentChargingPoint;
    private final Queue<Map.Entry<Long, Integer>> rsocHistory = new ConcurrentLinkedQueue<>();

    /**
     * Constructor for BatteryManagementService.
     *
     * @param commandService The service to interact with battery commands.
     * @param weatherService The service to fetch weather data.
     * @param chargingScheduleRepository The repository for accessing charging schedules.
     */
    public BatteryManagementService(BatteryCommandService commandService, OpenMeteoService weatherService,
                                    ChargingScheduleRepository chargingScheduleRepository) {
        this.commandService = commandService;
        this.weatherService = weatherService;
        this.chargingScheduleRepository = chargingScheduleRepository;
        this.batteryNotConfigured = commandService.isBatteryNotConfigured();
    }

    /**
     * Dynamically sets the charging point in watts.
     *
     * @param currentChargingPoint The desired charging point value in watts.
     * @return True if the charging point was set successfully, false otherwise.
     */
    public boolean setDynamicChargingPoint(int currentChargingPoint) {
        if (isBatteryNotConfigured()) {
            LogFilter.logWarn(BatteryManagementService.class, "Battery not configured. Cannot set charging point.");
            return false;
        }
        if (currentChargingPoint < 0) {
            LogFilter.logWarn(BatteryManagementService.class, "Invalid charging point value: {}. Charging point must be 0 or greater.", currentChargingPoint);
            return false;
        }

        ApiResponse<?> response = commandService.setChargePoint(currentChargingPoint);
        if (response.success()) {
            this.currentChargingPoint = currentChargingPoint;
            this.isReducedChargingActive = currentChargingPoint == chargingPointInWatt;
            LogFilter.logInfo(BatteryManagementService.class, "Charging point successfully set to {} Watt", currentChargingPoint);
            return true;
        } else {
            LogFilter.logError(BatteryManagementService.class, "Failed to set charging point to {} Watt. Response: {}", currentChargingPoint, response.message());
            return false;
        }
    }

    /**
     * Updates the RSOC (Relative State of Charge) history with the current value and timestamp.
     * Limits the history size to the configured maximum.
     *
     * @param currentTime The current timestamp in milliseconds.
     * @param currentRsoc The current RSOC in percentage.
     */
    public void updateRsocHistory(long currentTime, int currentRsoc) {
        if (currentRsoc < 0 || currentRsoc > 100) {
            LogFilter.logWarn(BatteryManagementService.class, "Invalid RSOC value: {}. Skipping update.", currentRsoc);
            return;
        }

        rsocHistory.add(new AbstractMap.SimpleEntry<>(currentTime, currentRsoc));
        while (rsocHistory.size() > maxHistorySize) {
            Map.Entry<Long, Integer> removed = rsocHistory.poll();
            if (removed != null) {
                LogFilter.logDebug(BatteryManagementService.class, "Removed oldest RSOC entry: {} at {}", removed.getValue(), new Date(removed.getKey()));
            }
        }
        LogFilter.logDebug(BatteryManagementService.class, "RSOC history updated: {} entries. Current RSOC: {} at {}",
                rsocHistory.size(), currentRsoc, new Date(currentTime));
    }

    /**
     * Initiates charging, either forced or based on a scheduled period.
     *
     * @param forceCharging If true, forces charging regardless of schedule.
     * @return True if charging was initiated successfully, false otherwise.
     */
    public boolean initCharging(boolean forceCharging) {
        this.forcedChargingActive = forceCharging;
        invalidateBatteryCache();

        if (!checkPreconditions()) {
            resetForcedCharging();
            return false;
        }

        long currentTime = System.currentTimeMillis();
        Optional<ChargingSchedule> activeSchedule = findActiveSchedule(currentTime);
        if (activeSchedule.isEmpty() && !forceCharging) {
            LogFilter.logInfo(BatteryManagementService.class, "No active charging schedule for the current time. Skipping charging.");
            resetForcedCharging();
            return false;
        }

        int rsoc = getRelativeStateOfCharge();
        if (rsoc >= targetStateOfCharge) {
            LogFilter.logInfo(BatteryManagementService.class, "Charging skipped: Battery charge level ({}) is at or above the target ({})", rsoc, targetStateOfCharge);
            resetForcedCharging();
            return false;
        }

        adjustChargingPointBasedOnWeather();
        ApiResponse<?> modeResponse = activateManualOperatingMode();
        if (!modeResponse.success()) {
            LogFilter.logError(BatteryManagementService.class, "Manual mode activation failed with response: {}", modeResponse.message());
            resetForcedCharging();
            return false;
        }

        if (!setDynamicChargingPoint(currentChargingPoint)) {
            LogFilter.logError(BatteryManagementService.class, "Failed to set dynamic charging point. API response unsuccessful.");
            resetForcedCharging();
            return false;
        }

        isReducedChargingActive = false;
        LogFilter.logInfo(BatteryManagementService.class, "Charging initiated successfully at {} Watt in {} mode", currentChargingPoint, forceCharging ? "Forced" : "Planned");
        return true;
    }

    /**
     * Resets the battery to automatic operating mode.
     *
     * @return True if reset was successful, false otherwise.
     */
    public boolean resetToAutomaticMode() {

        if (isBatteryNotConfigured()) {
            return false;
        }

        invalidateBatteryCache();
        if (isAutomaticOperatingMode()) {
            LogFilter.logDebug(BatteryManagementService.class, "Battery is not in manual mode, no need to switch to automatic mode.");
            return true;
        }

        if (!setDynamicChargingPoint(chargingPointInWatt)) {
            LogFilter.logInfo(BatteryManagementService.class, "Failed to return to standard chargingPointSetting.");
        }
        ApiResponse<?> response = activateAutomaticOperatingMode();
        if (!response.success()) {
            LogFilter.logInfo(BatteryManagementService.class, "Failed to activate automatic operating mode.");
            return false;
        }

        setForcedChargingActive(false);
        LogFilter.logInfo(BatteryManagementService.class, "Successfully returned to automatic operating mode and reset ChargingPoint to {} Watt", chargingPointInWatt);
        return true;
    }

    /**
     * Sets the forced charging flag.
     *
     * @param forcedChargingActive The new value for the forced charging flag.
     */
    public void setForcedChargingActive(boolean forcedChargingActive) {
        this.forcedChargingActive = forcedChargingActive;
    }

    /**
     * Retrieves the current relative state of charge (RSOC) with retry logic.
     *
     * @return The current RSOC in percentage, or a default value if retrieval fails.
     */
    public int getRelativeStateOfCharge() {
        if (isBatteryNotConfigured()) return 0;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                BatteryStatusResponse status = getCurrentBatteryStatus();
                if (status != null) {
                    int rsoc = status.getRsoc();
                    LogFilter.logInfo(BatteryManagementService.class, "Current relative state of charge (RSOC) is: {}", rsoc);
                    return rsoc;
                }
            } catch (Exception e) {
                LogFilter.logWarn(BatteryManagementService.class, "Failed to retrieve battery status. Attempt {} of {}. Retrying in 15 seconds...", attempt, MAX_RETRIES);
                sleepWithRetry(attempt);
            }
        }
        LogFilter.logError(BatteryManagementService.class, "Failed to retrieve battery status after {} attempts. Assuming RSOC is {}", MAX_RETRIES, DEFAULT_RSOC);
        return DEFAULT_RSOC;
    }

    /**
     * Checks if the battery is in automatic operating mode.
     *
     * @return True if in automatic mode, false otherwise.
     */
    public boolean isAutomaticOperatingMode() {
        if (isBatteryNotConfigured()) return false;
        BatteryStatusResponse status = getCurrentBatteryStatus();
        return status != null && OP_MODE_AUTOMATIC.equals(status.getOperatingMode());
    }

    /**
     * Activates manual operating mode.
     *
     * @return The API response from the command service.
     */
    public ApiResponse<?> activateManualOperatingMode() {
        invalidateBatteryCache();
        return commandService.setConfiguration(OPERATING_MODE, OP_MODE_MANUAL);
    }

    /**
     * Activates automatic operating mode.
     *
     * @return The API response from the command service.
     */
    public ApiResponse<?> activateAutomaticOperatingMode() {
        invalidateBatteryCache();
        return commandService.setConfiguration(OPERATING_MODE, OP_MODE_AUTOMATIC);
    }

    /**
     * Retrieves the current battery status, using cache if available.
     *
     * @return The current battery status, or null if unavailable.
     */
    public BatteryStatusResponse getCurrentBatteryStatus() {
        if (isBatteryNotConfigured()) return null;

        if (isCacheValid()) {
            LogFilter.logDebug(BatteryManagementService.class, "Returning cached battery status ({} seconds old)",
                    ChronoUnit.SECONDS.between(cacheTimestamp, Instant.now()));
            return cachedBatteryStatus;
        }

        ApiResponse<BatteryStatusResponse> response = commandService.getStatus();
        if (response != null && response.data() != null) {
            cachedBatteryStatus = response.data();
            cacheTimestamp = Instant.now();
            LogFilter.logDebug(BatteryManagementService.class, "Fetched new battery status");
            return cachedBatteryStatus;
        }
        return null;
    }

    /**
     * Checks if the battery is not configured.
     *
     * @return True if not configured, false otherwise.
     */
    public boolean isBatteryNotConfigured() {
        return batteryNotConfigured;
    }

    /**
     * Invalidates the battery status cache.
     */
    public void invalidateBatteryCache() {
        cachedBatteryStatus = null;
        cacheTimestamp = null;
        LogFilter.logDebug(BatteryManagementService.class, "Battery status cache invalidated.");
    }

    /**
     * Retrieves the RSOC history, ensuring validity and order.
     *
     * @return A list of RSOC history entries, or empty list if invalid.
     */
    public synchronized List<Map.Entry<Long, Integer>> getRsocHistory() {
        if (rsocHistory.isEmpty() || rsocHistory.size() < 2) {
            LogFilter.logWarn(BatteryManagementService.class, "RSOC history is {}. Unable to calculate time difference.",
                    rsocHistory.isEmpty() ? "empty" : "insufficient (only " + rsocHistory.size() + " entries)");
            return Collections.emptyList();
        }

        List<Map.Entry<Long, Integer>> history = new ArrayList<>(rsocHistory);
        long lastTimestamp = 0;
        for (Map.Entry<Long, Integer> entry : history) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                LogFilter.logError(BatteryManagementService.class, "Invalid entry in RSOC history. Resetting history.");
                rsocHistory.clear();
                return Collections.emptyList();
            }
            if (entry.getKey() < lastTimestamp) {
                LogFilter.logError(BatteryManagementService.class, "Invalid timestamp order in RSOC history. Resetting history.");
                rsocHistory.clear();
                return Collections.emptyList();
            }
            lastTimestamp = entry.getKey();
        }
        return history;
    }

    // Helper Methods

    /**
     * Checks preconditions for charging operations.
     *
     * @return True if preconditions are met, false otherwise.
     */
    private boolean checkPreconditions() {
        boolean configured = !isBatteryNotConfigured();
        if (!configured) {
            LogFilter.logInfo(BatteryManagementService.class, "Battery not configured. Skipping charging.");
        }
        return configured;
    }

    /**
     * Finds an active charging schedule for the given time.
     *
     * @param currentTime The current timestamp in milliseconds.
     * @return An optional containing the active schedule, or empty if none found.
     */
    private Optional<ChargingSchedule> findActiveSchedule(long currentTime) {
        return chargingScheduleRepository.findAll().stream()
                .filter(s -> currentTime >= s.getStartTimestamp() && currentTime < s.getEndTimestamp())
                .findFirst();
    }

    /**
     * Resets the forced charging flag to false.
     */
    private void resetForcedCharging() {
        setForcedChargingActive(false);
    }

    public boolean isManualIdleActive() {
        return manualIdleActive;
    }

    public void setManualIdleActive(boolean active) {
        this.manualIdleActive = active;
    }

    public boolean isForcedChargingActive() {
        return forcedChargingActive;
    }

    /**
     * Adjusts the charging point based on current weather conditions.
     */
    private void adjustChargingPointBasedOnWeather() {
        if (isNight(System.currentTimeMillis())) {
            LogFilter.logInfo(BatteryManagementService.class, "Nighttime detected. Skipping weather-based charging point adjustment.");
            currentChargingPoint = chargingPointInWatt;
            return;
        }

        Optional<Double> cloudCoverOpt = weatherService.getCurrentCloudCover();
        if (cloudCoverOpt.isEmpty()) {
            LogFilter.logWarn(BatteryManagementService.class, "No cloud cover data available. Using default charging point.");
            currentChargingPoint = chargingPointInWatt / 2;
            return;
        }

        double cloudCover = cloudCoverOpt.get();
        LogFilter.logInfo(BatteryManagementService.class, "Current cloud cover: {}. Evaluating charging strategy.", cloudCover);
        if (cloudCover <= 60.0) {
            LogFilter.logInfo(BatteryManagementService.class, "Cloud cover is {}. Switching to Automatic Mode for solar optimization.", cloudCover);
            resetToAutomaticMode();
        } else {
            currentChargingPoint = (int) Math.round((cloudCover / 100.0) * chargingPointInWatt);
            LogFilter.logInfo(BatteryManagementService.class, "Cloud cover is {}. Dynamically adjusted charging point to {} Watt.", cloudCover, currentChargingPoint);
        }
    }

    /**
     * Checks if the cached battery status is still valid.
     *
     * @return True if cache is valid, false otherwise.
     */
    private boolean isCacheValid() {
        return cachedBatteryStatus != null && cacheTimestamp != null &&
                ChronoUnit.SECONDS.between(cacheTimestamp, Instant.now()) <= cacheDurationInSeconds;
    }

    /**
     * Pauses execution for retry attempts with error handling.
     *
     * @param attempt The current retry attempt number.
     */
    private void sleepWithRetry(int attempt) {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogFilter.logError(BatteryManagementService.class, "Thread was interrupted during retry sleep after attempt {}.", attempt);
        }
    }
}