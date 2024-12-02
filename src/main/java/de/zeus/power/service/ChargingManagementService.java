package de.zeus.power.service;

import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.event.MarketPricesUpdatedEvent;
import de.zeus.power.repository.ChargingScheduleRepository;
import de.zeus.power.repository.MarketPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * ChargingManagementService handles the scheduling and optimization of charging tasks
 * based on market prices, day/night periods, and the current battery state.
 *
 * Copyright 2024 Guido Zeuner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
public class ChargingManagementService {

    private static final Logger logger = LoggerFactory.getLogger(ChargingManagementService.class);
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ChargingScheduleRepository chargingScheduleRepository;

    @Autowired
    private MarketPriceRepository marketPriceRepository;

    @Autowired
    private BatteryManagementService batteryManagementService;

    @Autowired
    private OpenMeteoService openMeteoService;

    @Autowired
    private TaskScheduler taskScheduler;

    @Value("${battery.target.stateOfCharge:90}")
    private int targetStateOfCharge;

    @Value("${marketdata.acceptable.price.cents:15}")
    private int maxAcceptableMarketPriceInCent;

    @Value("${battery.reduced.charge.factor:0.5}")
    private double reducedChargeFactor;

    @Value("${battery.capacity:10000}")
    private int totalBatteryCapacity;

    @Value("${weather.api.cloudcover.threshold:60}")
    private double cloudCoverThreshold;

    @Value("${night.start:22}")
    private int nightStartHour;

    @Value("${night.end:6}")
    private int nightEndHour;

    @Value("${daytime.preferred.start:10}")
    private int preferredStartHour;

    @Value("${daytime.preferred.end:15}")
    private int preferredEndHour;

    @Value("${daytime.weighting.bonus:0.3}")
    private double daytimeWeightingBonus;

    @Value("${battery.target.stateOfCharge}")
    private int targetStateOfChargeInPercent;

    private final AtomicBoolean resetScheduled = new AtomicBoolean(false);

    /**
     * Handles the MarketPricesUpdatedEvent and triggers the charging optimization process.
     *
     * @param event MarketPricesUpdatedEvent triggered when market prices are updated.
     */
    @EventListener
    public void onMarketPricesUpdated(MarketPricesUpdatedEvent event) {
        logger.info("Market prices updated event received. Recalculating charging schedule...");
        optimizeChargingSchedule();
    }

    @Scheduled(cron = "0 0 * * * ?") // Every full hour
    public void scheduledOptimizeChargingSchedule() {
        logger.info("Scheduled optimization of charging schedule triggered.");
        if (shouldOptimize()) {
            optimizeChargingSchedule();
        } else {
            logger.info("No significant changes detected. Skipping optimization.");
        }
        cleanUpExpiredSchedules();
    }

    @Scheduled(fixedRateString = "${battery.automatic.mode.check.interval:300000}") // Alle 5 Minuten
    public void checkAndResetToAutomaticMode() {
        if (batteryManagementService.isBatteryNotConfigured()) {
            logger.info("Battery not configured. Skipping automatic mode check.");
            return;
        }

        int currentRSOC = batteryManagementService.getRelativeStateOfCharge();
        if (currentRSOC < targetStateOfChargeInPercent) {
            logger.info("Current RSOC ({}) is below the target RSOC ({}). Skipping action.",
                    currentRSOC, targetStateOfChargeInPercent);
            return;
        }

        if (isNightPeriod() || batteryManagementService.isLargeConsumerActive()) {
            logger.info("Night period or large consumer detected. Setting charging point to 0.");
            boolean chargingPointReset = batteryManagementService.setDynamicChargingPoint(0);

            if (chargingPointReset) {
                logger.info("Charging point set to 0 successfully.");
                scheduleEndOfNightReset(); // Scheduler für Automatikmodus nach der Nacht
            } else {
                logger.error("Failed to set charging point to 0.");
            }
            return;
        }

        // Bedingungen für den Automatikmodus sind erfüllt
        boolean resetSuccessful = batteryManagementService.resetToAutomaticMode();
        if (resetSuccessful) {
            logger.info("Successfully switched back to automatic mode after reaching target RSOC.");
        } else {
            logger.error("Failed to switch back to automatic mode.");
        }
    }

    private void scheduleEndOfNightReset() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, nightEndHour);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        Date resetTime = calendar.getTime();
        if (resetTime.before(new Date())) {
            resetTime = new Date(resetTime.getTime() + 24 * 60 * 60 * 1000); // Auf den nächsten Tag verschieben
        }

        taskScheduler.schedule(() -> {
            logger.info("Night period ended. Switching back to automatic mode.");
            boolean resetSuccessful = batteryManagementService.resetToAutomaticMode();
            if (resetSuccessful) {
                logger.info("Successfully returned to automatic mode after night period.");
            } else {
                logger.error("Failed to switch to automatic mode after night period.");
            }
        }, resetTime);

        logger.info("Scheduled automatic mode reset at {}", dateFormat.format(resetTime));
    }


    /**
     * Schedules a periodic task to monitor the RSOC during a charging period.
     *
     * @param endTime The end time of the charging period.
     */
    private void scheduleRSOCMonitoring(Date endTime) {
        Runnable monitorTask = new Runnable() {
            @Override
            public void run() {
                int currentRSOC = batteryManagementService.getRelativeStateOfCharge();
                boolean largeConsumerActive = batteryManagementService.isLargeConsumerActive();
                logger.info("Monitoring RSOC during charging: Current RSOC = {}%, Target RSOC = {}%, LargeConsumerActive = {}",
                        currentRSOC, targetStateOfCharge, largeConsumerActive);

                // Check if RSOC has reached or exceeded the target
                if (currentRSOC >= targetStateOfCharge) {
                    logger.info("Target RSOC ({}) reached. Stopping charging.", targetStateOfCharge);
                    boolean stopSuccess = batteryManagementService.setDynamicChargingPoint(0);

                    if (stopSuccess) {
                        logger.info("Charging successfully stopped as RSOC reached the target.");
                    } else {
                        logger.error("Failed to stop charging despite reaching target RSOC.");
                    }

                    // Return to Automatic Mode
                    if (!isNightPeriod()) {
                        logger.info("Not nighttime and Large Consumer detected. Returning to Automatic Mode.");
                        batteryManagementService.resetToAutomaticMode();
                    }

                    // Cancel further checks for this period
                    return;
                }

                // Check if we are outside the night period and a large consumer is active
                if (!isNightPeriod() && largeConsumerActive) {
                    logger.info("Large consumer detected outside nighttime. Stopping charging and returning to Automatic Mode.");
                    batteryManagementService.setDynamicChargingPoint(0);
                    batteryManagementService.resetToAutomaticMode();
                    return;
                }

                // Reschedule the monitor task until the end of the charging period
                if (new Date().before(endTime)) {
                    taskScheduler.schedule(this, new Date(System.currentTimeMillis() + 5 * 60 * 1000)); // Check every 5 minutes
                } else {
                    logger.info("Charging period ended. Stopping RSOC monitoring.");
                }
            }
        };

        // Initial scheduling of the monitor task
        taskScheduler.schedule(monitorTask, new Date(System.currentTimeMillis() + 5 * 60 * 1000)); // Start monitoring after 5 minutes
    }


    /**
     * Checks whether a new optimization is required.
     *
     * @return True if changes have been detected since the last planning, otherwise False.
     */
    private boolean shouldOptimize() {
        int currentRSOC = batteryManagementService.getRelativeStateOfCharge();
        boolean hasLargeConsumer = batteryManagementService.isLargeConsumerActive();
        long currentTime = System.currentTimeMillis();

        // Check whether planned tasks are obsolete
        List<ChargingSchedule> activeSchedules = chargingScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getEndTimestamp() > currentTime)
                .collect(Collectors.toList());

        boolean hasFutureSchedules = !activeSchedules.isEmpty();

        logger.info("Optimization check: RSOC={}%, LargeConsumer={}, FutureSchedules={}",
                currentRSOC, hasLargeConsumer, hasFutureSchedules);

        // Trigger optimization only if:
        return !hasFutureSchedules || hasLargeConsumer || currentRSOC < targetStateOfCharge;
    }

    /**
     * Main method to optimize the charging schedule based on battery state, market prices, and time periods.
     */
    public void optimizeChargingSchedule() {
        logger.info("Starting optimization of charging schedule...");
        int currentRSOC = batteryManagementService.getRelativeStateOfCharge();

        if (currentRSOC >= targetStateOfCharge) {
            logger.info("Skipping optimization: Current RSOC ({}) already meets or exceeds target RSOC ({}).", currentRSOC, targetStateOfCharge);
            return;
        }

        planOptimizedCharging(currentRSOC);

        // Nighttime-specific optimization
        optimizeNighttimeCharging();

        logger.info("Optimization of charging schedule completed.");
    }

    private void optimizeNighttimeCharging() {
        logger.info("Optimizing nighttime charging to ensure the two cheapest periods are used.");

        List<MarketPrice> nighttimePrices = marketPriceRepository.findAll().stream()
                .filter(price -> isNighttimePeriod(price.getStartTimestamp())) // Extract startTimestamp
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh)) // Sort by price (ascending)
                .collect(Collectors.toList());

        if (nighttimePrices.size() < 2) {
            logger.warn("Not enough nighttime periods available for optimization.");
            return;
        }

        // Select the two cheapest periods
        List<MarketPrice> selectedNighttimePeriods = nighttimePrices.subList(0, 2);

        // Remove less optimal nighttime schedules
        List<ChargingSchedule> existingNighttimeSchedules = chargingScheduleRepository.findAll().stream()
                .filter(schedule -> isNighttimePeriod(schedule.getStartTimestamp()))
                .collect(Collectors.toList());

        for (ChargingSchedule schedule : existingNighttimeSchedules) {
            if (selectedNighttimePeriods.stream().noneMatch(p -> p.getStartTimestamp() == schedule.getStartTimestamp())) {
                logger.info("Removing less optimal nighttime schedule: {} - {}.",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp())));
                chargingScheduleRepository.delete(schedule);
            }
        }

        // Schedule the two cheapest nighttime periods
        saveChargingSchedule(selectedNighttimePeriods);

        for (MarketPrice period : selectedNighttimePeriods) {
            logger.info("Scheduling charging task for optimized nighttime period: {} - {} at {} cents/kWh.",
                    dateFormat.format(new Date(period.getStartTimestamp())),
                    dateFormat.format(new Date(period.getEndTimestamp())),
                    period.getPriceInCentPerKWh());
            taskScheduler.schedule(() -> executeChargingTask(
                    new Date(period.getStartTimestamp()),
                    new Date(period.getEndTimestamp())), new Date(period.getStartTimestamp()));
        }
    }

    private boolean isNighttimePeriod(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        return (nightStartHour <= nightEndHour)
                ? hour >= nightStartHour && hour < nightEndHour
                : hour >= nightStartHour || hour < nightEndHour;
    }

    /**
     * Plans and schedules optimized charging for both day and night periods
     * based on market prices, weather conditions, and the current state of charge (RSOC).
     *
     * @param currentRSOC Current battery state of charge as a percentage.
     */
    private void planOptimizedCharging(int currentRSOC) {
        logger.info("Planning optimized charging...");

        // Fetch all available market prices
        List<MarketPrice> marketPrices = marketPriceRepository.findAll();
        if (marketPrices.isEmpty()) {
            logger.warn("No market prices available for charging.");
            return;
        }

        long currentTime = System.currentTimeMillis();
        Optional<Double> cloudCover = openMeteoService.getCurrentCloudCover();

        // Filter valid charging periods based on time and price thresholds
        List<MarketPrice> validPeriods = marketPrices.stream()
                .filter(p -> p.getStartTimestamp() > currentTime) // Only future periods
                .filter(p -> p.getPriceInCentPerKWh() <= maxAcceptableMarketPriceInCent) // Within price threshold
                .collect(Collectors.toList());

        if (validPeriods.isEmpty()) {
            logger.info("No suitable periods found for charging.");
            return;
        }

        boolean optimalSolarConditions = false;

        // Check if it is daytime and weather data is available
        if (!isNightPeriod() && cloudCover.isPresent()) {
            double currentCloudCover = cloudCover.get();
            logger.info("Current cloud cover: {}%, threshold: {}%", currentCloudCover, cloudCoverThreshold);

            // Check if cloud cover exceeds the threshold for optimal solar charging
            if (currentCloudCover >= cloudCoverThreshold) {
                logger.warn("Cloud cover exceeds or equals threshold ({}%). Planning reduced network charging.", cloudCoverThreshold);

                // Reduce charging point dynamically due to high cloud cover
                boolean reducedChargeSet = batteryManagementService.setReducedChargePoint();
                if (reducedChargeSet) {
                    logger.info("Charging point successfully reduced due to high cloud cover.");
                } else {
                    logger.error("Failed to set reduced charging point under high cloud cover.");
                }
            } else {
                // Optimal solar conditions detected, but we will still plan future charging tasks
                logger.info("Optimal solar conditions detected. Charging tasks will be scheduled but may be skipped if conditions remain optimal.");
                optimalSolarConditions = true; // Set the flag
            }

            // Apply additional filtering to prioritize preferred daytime periods
            validPeriods = filterPreferredDaytimePeriods(validPeriods);
            logger.info("Filtered preferred daytime periods: {}",
                    validPeriods.stream()
                            .map(p -> String.format("%s - %s (%.2f cents/kWh)",
                                    dateFormat.format(new Date(p.getStartTimestamp())),
                                    dateFormat.format(new Date(p.getEndTimestamp())),
                                    p.getPriceInCentPerKWh()))
                            .collect(Collectors.joining(", ")));
        } else {
            logger.warn("Ignoring weather data as it is nighttime or unavailable.");
        }

        // Calculate the required charging time based on the current and target RSOC
        int requiredChargingTime = calculateRequiredChargingTime(currentRSOC, targetStateOfCharge);

        // Select optimal charging periods based on calculated requirements
        List<MarketPrice> selectedPeriods = selectOptimalPeriods(validPeriods, requiredChargingTime);

        // Handle the scenario where target RSOC has been reached and no large consumers are active
        if (currentRSOC >= targetStateOfCharge && !batteryManagementService.isLargeConsumerActive()) {
            logger.info("TargetStateOfCharge ({}) reached without large consumer. Activating AutomaticMode.", targetStateOfCharge);
            batteryManagementService.resetToAutomaticMode();
            return; // No further action needed as the target is already achieved
        }

        if (selectedPeriods.isEmpty()) {
            logger.warn("No suitable periods selected after applying constraints.");
            return;
        }

        logger.info("Selected periods for charging: {}",
                selectedPeriods.stream()
                        .map(p -> String.format("%s - %s (%.2f cents/kWh)",
                                dateFormat.format(new Date(p.getStartTimestamp())),
                                dateFormat.format(new Date(p.getEndTimestamp())),
                                p.getPriceInCentPerKWh()))
                        .collect(Collectors.joining(", ")));

        // Save the planned charging schedule to the database
        saveChargingSchedule(selectedPeriods);

        // Schedule charging tasks
        for (MarketPrice period : selectedPeriods) {
            Date startTime = new Date(period.getStartTimestamp());
            Date endTime = new Date(period.getEndTimestamp());

            // Schedule charging tasks regardless of current conditions
            logger.info("Scheduling charging task for period: {} - {}.", dateFormat.format(startTime), dateFormat.format(endTime));
            taskScheduler.schedule(() -> executeChargingTask(startTime, endTime), startTime);
        }

        // Schedule post-charging reset if necessary
        schedulePostChargingReset();
    }


    /**
     * Filters the given list of market prices to include only those that fall within the preferred daytime hours.
     *
     * @param periods List of market prices to filter.
     * @return List of market prices that fall within the preferred daytime hours.
     */
    private List<MarketPrice> filterPreferredDaytimePeriods(List<MarketPrice> periods) {
        Calendar calendar = Calendar.getInstance();

        // List to hold periods that fall within the preferred daytime hours
        List<MarketPrice> filteredPeriods = new ArrayList<>();

        for (MarketPrice period : periods) {
            // Extract the start hour of the period
            calendar.setTimeInMillis(period.getStartTimestamp());
            int startHour = calendar.get(Calendar.HOUR_OF_DAY);

            // Extract the end hour of the period
            calendar.setTimeInMillis(period.getEndTimestamp());
            int endHour = calendar.get(Calendar.HOUR_OF_DAY);

            // Check if the period falls entirely within the preferred daytime hours
            if (startHour >= preferredStartHour && endHour <= preferredEndHour) {
                filteredPeriods.add(period);
            }
        }

        return filteredPeriods;
    }


    /**
     * Determines if the current time falls within the configured nighttime period.
     *
     * @return True if the current time is within the nighttime hours, false otherwise.
     */
    private boolean isNightPeriod() {
        Calendar calendar = Calendar.getInstance();
        int currentHour = calendar.get(Calendar.HOUR_OF_DAY);

        // Check if the current hour is within the nighttime range
        if (nightStartHour <= nightEndHour) {
            // Night period does not cross midnight
            return currentHour >= nightStartHour && currentHour < nightEndHour;
        } else {
            // Night period crosses midnight
            return currentHour >= nightStartHour || currentHour < nightEndHour;
        }
    }


    /**
     * Schedules reduced power charging tasks and saves the planned periods.
     *
     * @param plannedPeriods Selected charging periods.
     */
    private void scheduleChargingTasksWithReducedPower(List<MarketPrice> plannedPeriods) {
        long currentTime = System.currentTimeMillis();

        for (MarketPrice period : plannedPeriods) {
            Date startTime = new Date(period.getStartTimestamp());
            Date endTime = new Date(period.getEndTimestamp());

            if (period.getStartTimestamp() <= currentTime) {
                logger.warn("Skipping task for period {} - {}: Start time is in the past.",
                        dateFormat.format(new Date(period.getStartTimestamp())),
                        dateFormat.format(new Date(period.getEndTimestamp())));
                continue;
            }

            if (period.getStartTimestamp() >= (currentTime + 24 * 60 * 60 * 1000)) {
                logger.info("Skipping task for period {} - {}: Start time is scheduled for tomorrow or later.",
                        dateFormat.format(new Date(period.getStartTimestamp())),
                        dateFormat.format(new Date(period.getEndTimestamp())));
                continue;
            }

            // Save the planned loading time
            saveChargingSchedule(Collections.singletonList(period));

            // Scheduling the charging process with reduced power
            taskScheduler.schedule(() -> {
                logger.info("Scheduled reduced power charging task started for period: {} - {}.",
                        dateFormat.format(startTime), dateFormat.format(endTime));

                batteryManagementService.setReducedChargePoint();
                batteryManagementService.initCharging(true);

                // Set ChargingPoint to 0 after the end of the charging period
                taskScheduler.schedule(() -> {
                    logger.info("Reduced power charging task ended. Setting ChargingPoint to 0.");
                    boolean resetSuccess = batteryManagementService.setDynamicChargingPoint(0);
                    if (resetSuccess) {
                        logger.info("ChargingPoint successfully set to 0 after reduced charging.");
                    } else {
                        logger.error("Failed to set ChargingPoint to 0 after reduced charging.");
                    }
                }, endTime);

            }, startTime);

            logger.info("Reduced power charging task scheduled for period: {} - {}.",
                    dateFormat.format(startTime), dateFormat.format(endTime));
        }

        logger.info("All reduced power charging tasks successfully scheduled.");
    }

    /**
     * Schedules a task to reset to default charge point and enable automatic mode at the end of the night period.
     */
    private void scheduleAutomaticModeReset() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, nightEndHour);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        Date resetTime = calendar.getTime();
        if (resetTime.before(new Date())) {
            resetTime = new Date(resetTime.getTime() + 24 * 60 * 60 * 1000); // Add one day if already past
        }

        // Declare resetTime as final for lambda expression
        final Date finalResetTime = resetTime;

        taskScheduler.schedule(() -> {
            logger.info("Re-enabling automatic mode and resetting charging point at {}", dateFormat.format(finalResetTime));
            batteryManagementService.resetToAutomaticMode();

        }, finalResetTime);

        logger.info("Task to re-enable automatic mode and reset charge point scheduled for {}", dateFormat.format(finalResetTime));
    }

    private void schedulePostChargingReset() {
        // Check whether a reset is already planned
        if (resetScheduled.get()) {
            logger.info("Reset task is already scheduled. Skipping duplicate scheduling.");
            return;
        }

        // Get all scheduled entries and sort them by end time
        List<ChargingSchedule> schedules = chargingScheduleRepository.findAll().stream()
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());

        if (schedules.isEmpty()) {
            logger.info("No scheduled charging tasks found. Resetting charging point immediately.");
            batteryManagementService.resetToAutomaticMode();
            return;
        }

        // List for the end times that require a reset
        List<Long> resetTimes = new ArrayList<>();

        // Process the planned periods
        long blockEndTime = schedules.get(0).getEndTimestamp(); // Initial end time of the first block

        for (int i = 1; i < schedules.size(); i++) {
            long gap = schedules.get(i).getStartTimestamp() - blockEndTime;

            if (gap > 15 * 60 * 1000) { // Gap greater than 15 minutes detected
                // Add the end time of the current block to the reset list
                resetTimes.add(blockEndTime);
                logger.info("Block end detected. Scheduling reset after last block ends at {}", dateFormat.format(new Date(blockEndTime + 1000)));

                // Start a new block
                blockEndTime = schedules.get(i).getEndTimestamp();
            } else {
                // Update the end time of the current block
                blockEndTime = Math.max(blockEndTime, schedules.get(i).getEndTimestamp());
            }
        }

        // Add the last block to the reset list
        resetTimes.add(blockEndTime);
        logger.info("Final block detected. Scheduling reset after last block ends at {}", dateFormat.format(new Date(blockEndTime + 1000)));

        // Schedule resets for all times contained in the list
        for (Long resetTime : resetTimes) {
            scheduleResetTask(new Date(resetTime + 1000)); // Schedule one second after the end time
        }
    }

    /**
     * Planen eines Resets nach einer Ladesitzung.
     *
     * @param resetTime Zeitpunkt des Resets.
     */
    private void scheduleResetTask(Date resetTime) {
        if (resetScheduled.get()) {
            logger.info("Reset task is already scheduled. Skipping duplicate scheduling.");
            return;
        }

        taskScheduler.schedule(() -> {
            logger.info("Re-enabling automatic mode and resetting charging point at {}", dateFormat.format(resetTime));
            batteryManagementService.resetToAutomaticMode();
            resetScheduled.set(false);

        }, resetTime);

        resetScheduled.set(true);
        logger.info("Task to reset charging point and re-enable automatic mode scheduled for {}", dateFormat.format(resetTime));
    }


    /**
     * Selects optimal periods for charging based on required charging time, allowing partial utilization of periods.
     *
     * @param periods              Available market periods.
     * @param requiredRSOC         Target charging level in %
     * @return A list of optimal periods for charging.
     */
    private List<MarketPrice> selectOptimalPeriods(List<MarketPrice> periods, int requiredRSOC) {
        List<MarketPrice> selectedPeriods = new ArrayList<>();
        int accumulatedRSOC = 0;

        logger.info("Selecting optimal periods based on required RSOC: {}%.", requiredRSOC);

        // Sort periods by price and daytime preference
        periods = periods.stream()
                .sorted((p1, p2) -> {
                    double effectivePrice1 = isPreferredDaytimePeriod(p1)
                            ? p1.getPriceInCentPerKWh() - daytimeWeightingBonus
                            : p1.getPriceInCentPerKWh();
                    double effectivePrice2 = isPreferredDaytimePeriod(p2)
                            ? p2.getPriceInCentPerKWh() - daytimeWeightingBonus
                            : p2.getPriceInCentPerKWh();
                    return Double.compare(effectivePrice1, effectivePrice2);
                })
                .collect(Collectors.toList());

        for (MarketPrice period : periods) {
            int periodRSOC = calculatePeriodRSOC(period);

            if (accumulatedRSOC + periodRSOC >= requiredRSOC) {
                logger.info("Partially utilizing period {} - {} for {}% RSOC.",
                        dateFormat.format(new Date(period.getStartTimestamp())),
                        dateFormat.format(new Date(period.getEndTimestamp())),
                        requiredRSOC - accumulatedRSOC);
                selectedPeriods.add(period);
                break; // Target RSOC achieved
            } else {
                logger.info("Added full period {} - {} for {}% RSOC. Remaining: {}%.",
                        dateFormat.format(new Date(period.getStartTimestamp())),
                        dateFormat.format(new Date(period.getEndTimestamp())),
                        periodRSOC, requiredRSOC - (accumulatedRSOC + periodRSOC));
                selectedPeriods.add(period);
                accumulatedRSOC += periodRSOC;
            }
        }

        return selectedPeriods;
    }

    private int calculatePeriodRSOC(MarketPrice period) {
        double chargingPowerInWatt = batteryManagementService.getChargingPointInWatt();
        double periodDurationInHours = (period.getEndTimestamp() - period.getStartTimestamp()) / 3600_000.0;
        double periodEnergyWh = chargingPowerInWatt * periodDurationInHours;

        int periodRSOC = (int) Math.round((periodEnergyWh / totalBatteryCapacity) * 100);

        logger.debug("Calculated RSOC contribution for period {} - {}: {}%.",
                dateFormat.format(new Date(period.getStartTimestamp())),
                dateFormat.format(new Date(period.getEndTimestamp())),
                periodRSOC);

        return periodRSOC;
    }


    /**
     * Checks if the given period falls entirely within the preferred daytime period.
     *
     * @param period The MarketPrice period to check.
     * @return True if the period is within the preferred daytime hours, false otherwise.
     */
    private boolean isPreferredDaytimePeriod(MarketPrice period) {
        Calendar calendar = Calendar.getInstance();

        // Extract the start hour of the period
        calendar.setTimeInMillis(period.getStartTimestamp());
        int startHour = calendar.get(Calendar.HOUR_OF_DAY);

        // Extract the end hour of the period
        calendar.setTimeInMillis(period.getEndTimestamp());
        int endHour = calendar.get(Calendar.HOUR_OF_DAY);

        // Check if the entire period is within the preferred daytime hours
        return startHour >= preferredStartHour && endHour <= preferredEndHour;
    }


    /**
     * Cleans up expired charging schedules.
     */
    private void cleanUpExpiredSchedules() {
        List<ChargingSchedule> expiredSchedules = chargingScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getEndTimestamp() < System.currentTimeMillis())
                .collect(Collectors.toList());

        expiredSchedules.forEach(schedule -> {
            logger.info("Removing expired charging schedule: {} - {}.",
                    dateFormat.format(new Date(schedule.getStartTimestamp())),
                    dateFormat.format(new Date(schedule.getEndTimestamp())));
            chargingScheduleRepository.delete(schedule);
        });
    }

    /**
     * Schedules charging tasks, ensuring no duplicate tasks are planned.
     *
     * @param plannedPeriods Selected charging periods.
     */
    private void scheduleChargingTasks(List<MarketPrice> plannedPeriods) {
        long currentTime = System.currentTimeMillis();

        for (MarketPrice period : plannedPeriods) {
            Date startTime = new Date(period.getStartTimestamp());
            Date endTime = new Date(period.getEndTimestamp());

            // Skip tasks if the start time is in the past
            if (period.getStartTimestamp() <= currentTime) {
                logSkippedTask("Start time is in the past", startTime, endTime);
                continue;
            }

            // Skip tasks if the start time is scheduled for tomorrow or later
            if (period.getStartTimestamp() >= (currentTime + 24 * 60 * 60 * 1000)) {
                logSkippedTask("Start time is scheduled for tomorrow or later", startTime, endTime);
                continue;
            }

            // Skip tasks if they are duplicates
            if (isDuplicateTask(period)) {
                logSkippedTask("Duplicate task detected", startTime, endTime);
                continue;
            }

            // Handle active charging scenarios
            if (shouldContinueCharging()) {
                continueChargingIfAffordable(period);
            }

            // Schedule the task
            taskScheduler.schedule(() -> executeChargingTask(startTime, endTime), startTime);
            logger.info("Task scheduled for period: {} - {}.", dateFormat.format(startTime), dateFormat.format(endTime));
        }

        logger.info("All charging tasks successfully scheduled.");
    }

    /**
     * Logs skipped tasks with a specific reason.
     *
     * @param reason    The reason for skipping the task.
     * @param startTime The start time of the task.
     * @param endTime   The end time of the task.
     */
    private void logSkippedTask(String reason, Date startTime, Date endTime) {
        logger.warn("Skipping task for period {} - {}: {}.",
                dateFormat.format(startTime),
                dateFormat.format(endTime),
                reason);
    }


    /**
     * Determines if charging should continue based on the current battery state and operating mode.
     */
    private boolean shouldContinueCharging() {
        if (batteryManagementService.isBatteryCharging()) {
            logger.info("Battery is already charging using solar energy. Preventing additional grid charging.");
            return false;
        }

        if (batteryManagementService.getRelativeStateOfCharge() >= targetStateOfCharge) {
            logger.info("Battery has already reached target state of charge. No additional charging required.");
            return false;
        }

        if (batteryManagementService.isManualOperatingMode()) {
            logger.info("Manual mode: Continuing charging for period.");
        } else if (batteryManagementService.isAutomaticOperatingMode()) {
            logger.info("Automatic mode: Using free solar energy for period.");
        } else {
            logger.warn("Unknown operating mode. Skipping further charging logic.");
            return false;
        }
        return true;
    }


    /**
     * Executes a scheduled charging task.
     */
    private void executeChargingTask(Date startTime, Date endTime) {
        logger.info("Scheduled charging task started for period: {} - {}.",
                dateFormat.format(startTime), dateFormat.format(endTime));

        batteryManagementService.initCharging(false);
        scheduleRSOCMonitoring(endTime);
    }


    private void continueChargingIfAffordable(MarketPrice currentPeriod) {
        List<MarketPrice> subsequentPeriods = marketPriceRepository.findAll().stream()
                .filter(p -> p.getStartTimestamp() > currentPeriod.getEndTimestamp())
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .collect(Collectors.toList());

        if (subsequentPeriods.isEmpty()) {
            logger.info("No subsequent periods available for continued charging.");
            return;
        }

        MarketPrice nextPeriod = subsequentPeriods.get(0);
        if (nextPeriod.getPriceInCentPerKWh() <= currentPeriod.getPriceInCentPerKWh() * 1.3) {
            logger.info("Scheduling next charging period: {} - {} at {} cents/kWh.",
                    dateFormat.format(new Date(nextPeriod.getStartTimestamp())),
                    dateFormat.format(new Date(nextPeriod.getEndTimestamp())),
                    nextPeriod.getPriceInCentPerKWh());

            taskScheduler.schedule(() -> {
                logger.info("Continued charging started for period: {} - {}.",
                        dateFormat.format(new Date(nextPeriod.getStartTimestamp())),
                        dateFormat.format(new Date(nextPeriod.getEndTimestamp())));
                batteryManagementService.initCharging(false);
            }, new Date(nextPeriod.getStartTimestamp()));
        } else {
            logger.info("Skipping subsequent charging period: Price increase exceeds 30% threshold.");
        }
    }

    /**
     * Checks if a task for the given market price has already been scheduled.
     *
     * @param marketPrice The market price for the task.
     * @return True if the task is already scheduled, false otherwise.
     */
    private boolean isDuplicateTask(MarketPrice marketPrice) {
        return chargingScheduleRepository.findAll().stream()
                .anyMatch(existingSchedule ->
                        existingSchedule.getStartTimestamp() == marketPrice.getStartTimestamp() &&
                                existingSchedule.getEndTimestamp() == marketPrice.getEndTimestamp() &&
                                Double.compare(existingSchedule.getPrice(), marketPrice.getPriceInCentPerKWh()) == 0);
    }

    /**
     * Saves the selected charging periods to the database, avoiding duplicate entries.
     *
     * @param periods Selected charging periods.
     */
    private void saveChargingSchedule(List<MarketPrice> periods) {
        logger.info("Saving planned charging schedules...");
        for (MarketPrice period : periods) {
            // Check whether an entry with an identical start, end and price already exists
            boolean isDuplicate = chargingScheduleRepository.existsByStartEndAndPrice(
                    period.getStartTimestamp(),
                    period.getEndTimestamp(),
                    period.getPriceInCentPerKWh()
            );

            if (isDuplicate) {
                logger.info("Duplicate schedule detected. Skipping: {} - {} at {} cents/kWh.",
                        dateFormat.format(new Date(period.getStartTimestamp())),
                        dateFormat.format(new Date(period.getEndTimestamp())),
                        period.getPriceInCentPerKWh());
                continue;
            }

            // Create and save a new loading interval
            ChargingSchedule schedule = new ChargingSchedule();
            schedule.setStartTimestamp(period.getStartTimestamp());
            schedule.setEndTimestamp(period.getEndTimestamp());
            schedule.setPrice(period.getPriceInCentPerKWh());

            try {
                chargingScheduleRepository.save(schedule);
                logger.info("Saved charging schedule: {} - {} at {} cents/kWh.",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp())),
                        schedule.getPrice());
            } catch (Exception e) {
                logger.error("Failed to save charging schedule: {} - {} at {} cents/kWh. Error: {}",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp())),
                        schedule.getPrice(),
                        e.getMessage());
            }
        }
        logger.info("Charging schedules saving completed.");
    }


    /**
     * Calculates the required charging time based on the target and current state of charge.
     *
     * @param currentRSOC Current state of charge.
     * @param targetRSOC  Target state of charge.
     * @return Required charging time in minutes.
     */
    private int calculateRequiredChargingTime(int currentRSOC, int targetRSOC) {
        // Check whether the target has already been reached or exceeded
        if (currentRSOC >= targetRSOC) {
            logger.info("No charging required: currentRSOC ({}) >= targetRSOC ({}).", currentRSOC, targetRSOC);
            return 0; // No charging time required
        }

        // Calculation of the remaining capacity
        int remainingCapacityWh = batteryManagementService.getRemainingCapacityWh() * (targetRSOC - currentRSOC) / 100;
        double chargingPointInWatt = batteryManagementService.getChargingPointInWatt();

        // Calculation of the charging time in minutes
        int chargingTime = (int) Math.ceil((double) remainingCapacityWh / chargingPointInWatt * 60);

        logger.info("Calculating required charging time: currentRSOC={}, targetRSOC={}, remainingCapacityWh={}, chargingPointInWatt={}, chargingTime={}",
                currentRSOC, targetRSOC, remainingCapacityWh, chargingPointInWatt, chargingTime);

        return chargingTime;
    }

    /**
     * Retrieves and sorts all existing charging schedules.
     *
     * @return A sorted list of existing charging schedules.
     */
    public List<ChargingSchedule> getSortedChargingSchedules() {
        return chargingScheduleRepository.findAll().stream()
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());
    }
}
