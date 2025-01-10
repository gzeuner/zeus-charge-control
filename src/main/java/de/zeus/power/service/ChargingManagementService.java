package de.zeus.power.service;

import de.zeus.power.config.LogFilter;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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

    private Boolean cachedNighttimeWindow; // Cached result for the last nighttime check
    private long cacheTimestamp;          // Timestamp of the last cache update
    private static final long CACHE_DURATION_MS = 60000; // Cache validity duration (1 minute)
    private static final Logger logger = LoggerFactory.getLogger(ChargingManagementService.class);
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ChargingScheduleRepository chargingScheduleRepository;

    @Autowired
    private MarketPriceRepository marketPriceRepository;

    @Autowired
    private BatteryManagementService batteryManagementService;

    @Autowired
    private TaskScheduler taskScheduler;

    @Value("${battery.target.stateOfCharge:90}")
    private int targetStateOfCharge;

    @Value("${marketdata.acceptable.price.cents:15}")
    private int maxAcceptableMarketPriceInCent;

    @Value("${night.start:22}")
    private int nightStartHour;

    @Value("${night.end:6}")
    private int nightEndHour;

    @Value("${marketdata.price.flexibility.threshold:10}")
    private double priceFlexibilityThreshold;

    private final List<ChargingSchedule> daytimeBuffer = new CopyOnWriteArrayList<>();

    private final AtomicBoolean nightResetScheduled = new AtomicBoolean(false);

    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * Handles the MarketPricesUpdatedEvent and triggers the charging optimization process.
     *
     * @param event MarketPricesUpdatedEvent triggered when market prices are updated.
     */
    @EventListener
    public void onMarketPricesUpdated(MarketPricesUpdatedEvent event) {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Market prices updated event received. Recalculating charging schedule...");

        //Precalculate planning
        bufferDaytimeCharging();
        // Perform optimization
        optimizeChargingSchedule();
        // Ensure return to automatic mode
        scheduleEndOfNightReset();

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Market price update handled and schedules synchronized.");
    }

    @Scheduled(cron = "0 0 * * * ?") // Every full hour
    public void scheduledOptimizeChargingSchedule() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Scheduled optimization of charging schedule triggered.");
        if (shouldOptimize()) {
            optimizeChargingSchedule();
        } else {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "No significant changes detected. Skipping optimization.");
        }
        cleanUpExpiredSchedules();
    }

    /**
     * Optimizes the charging schedule by combining nighttime and daytime optimizations.
     * Also dynamically evaluates the battery's current relative state of charge (RSOC)
     * to decide if additional charging is required to reach the target RSOC.
     *
     * Steps:
     * 1. Collect and optimize schedules for nighttime and daytime periods.
     * 2. Calculate a dynamic RSOC threshold and compare it with the current RSOC.
     * 3. If the current RSOC is below the threshold, initiate additional charging.
     * 4. Synchronize the optimized schedules with the existing ones.
     */
    private void optimizeChargingSchedule() {
        logger.info("Starting optimization of charging schedule...");

        // Schedule EndOfNightReset
        scheduleEndOfNightReset();

        // Initialize a set to hold optimized schedules
        Set<ChargingSchedule> optimizedSchedules = new HashSet<>();

        // Add nighttime charging optimization results
        optimizedSchedules.addAll(optimizeNighttimeCharging());

        // Add daytime charging optimization results
        optimizedSchedules.addAll(optimizeDaytimeCharging());

        // Calculate a dynamic threshold for RSOC and get the current RSOC
        int dynamicThreshold = calculateDynamicDaytimeThreshold();
        int currentRSOC = batteryManagementService.getRelativeStateOfCharge();

        // Check if additional charging is needed based on RSOC and threshold
        if (currentRSOC <= dynamicThreshold) {
            logger.info("RSOC is below the dynamic threshold (current: {}%, threshold: {}%). Initiating charging to reach target RSOC ({}%).",
                    currentRSOC, dynamicThreshold, targetStateOfCharge);

            // Add planned charges from the daytime buffer if below the target RSOC
            if (currentRSOC < targetStateOfCharge) {
                logger.info("Scheduling additional charging to reach the target RSOC.");
                optimizedSchedules.addAll(daytimeBuffer);
            }
        } else {
            logger.info("RSOC is above the dynamic threshold (current: {}%, threshold: {}%). Skipping additional daytime charging.",
                    currentRSOC, dynamicThreshold);
        }

        // Synchronize the optimized schedules with the system
        synchronizeSchedules(optimizedSchedules);

        logger.info("Charging schedule optimization completed.");
    }

    /**
     * Buffers daytime charging periods dynamically based on market price analysis.
     * Selects a restricted number of the best charging periods depending on RSOC and dynamic thresholds.
     */
    private void bufferDaytimeCharging() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Buffering daytime charging periods dynamically based on price analysis.");

        long currentTime = System.currentTimeMillis();

        // Filter market prices that are outside the nighttime window and in the future
        List<MarketPrice> daytimePeriods = marketPriceRepository.findAll().stream()
                .filter(price -> !isWithinNighttimeWindow(price.getStartTimestamp())) // Exclude nighttime periods
                .filter(price -> price.getStartTimestamp() > currentTime) // Only future periods
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh)) // Sort by price
                .toList();

        if (daytimePeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No daytime periods available for buffering.");
            daytimeBuffer.clear();
            return;
        }

        // Calculate dynamic thresholds
        double threshold = calculateDynamicThreshold(daytimePeriods, priceFlexibilityThreshold);
        double maxAcceptablePrice = calculateMaxAcceptablePrice(
                batteryManagementService.getRelativeStateOfCharge(),
                maxAcceptableMarketPriceInCent
        );

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Dynamic daytime threshold: %.2f cents/kWh, Max acceptable price: %.2f cents/kWh",
                threshold, maxAcceptablePrice
        ));

        // Dynamically determine the number of periods to select based on RSOC and restrict further
        int currentRSOC = batteryManagementService.getRelativeStateOfCharge();
        int maxPeriods = calculateMaxPeriodsBasedOnRSOC(currentRSOC, true);

        // Select the best periods based on price and thresholds
        List<MarketPrice> selectedPeriods = daytimePeriods.stream()
                .filter(price -> price.getPriceInCentPerKWh() <= threshold)
                .filter(price -> price.getPriceInCentPerKWh() <= maxAcceptablePrice)
                .limit(maxPeriods)
                .toList();

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Selected %d daytime periods based on tighter restrictions.", selectedPeriods.size()
        ));

        // Update the daytime buffer
        daytimeBuffer.clear();
        daytimeBuffer.addAll(collectAndOptimizeSchedules(selectedPeriods));

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Buffered %d daytime schedules for potential use.", daytimeBuffer.size()
        ));
    }


    /**
     * Periodically checks the system state and ensures proper transitions between modes.
     * Adjusts nighttime schedules and ensures reset to automatic mode at night end.
     */
    @Scheduled(fixedRateString = "${battery.automatic.mode.check.interval:300000}") // Every 5 minutes
    public void checkAndResetToAutomaticMode() {
        if (batteryManagementService.isBatteryNotConfigured()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Battery not configured. Skipping automatic mode check.");
            return;
        }

        boolean isNightTime = isWithinNighttimeWindow(System.currentTimeMillis());

        // Reset to automatic mode if nighttime has ended
        if (!isNightTime && batteryManagementService.isManualOperatingMode()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Night period ended. Returning to automatic mode.");
            batteryManagementService.resetToAutomaticMode();
        }
    }

    /**
     * Schedules a reset to automatic mode 15 minutes before the end of the nighttime period.
     * Ensures that no duplicate resets are scheduled for the same night's end.
     */
    private synchronized void scheduleEndOfNightReset() {
        if (nightResetScheduled.get()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Night reset already scheduled. Skipping duplicate scheduling.");
            return;
        }

        // Calculate the reset time: 15 minutes before the end of nighttime
        Calendar nightStart = getNightStart();
        Calendar nightEnd = getNightEnd(nightStart);
        nightEnd.add(Calendar.MINUTE, -15);

        long resetTimestamp = nightEnd.getTimeInMillis();
        long currentTime = System.currentTimeMillis();
        if (resetTimestamp <= currentTime) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "End of night is in the past. No reset scheduled.");
            return;
        }

        // Schedule the reset to automatic mode
        Date resetTime = new Date(resetTimestamp);
        taskScheduler.schedule(() -> {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Night period ending. Switching to automatic mode.");
            boolean resetSuccessful = batteryManagementService.resetToAutomaticMode();
            if (resetSuccessful) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Successfully returned to automatic mode after night period.");
            } else {
                LogFilter.log(LogFilter.LOG_LEVEL_ERROR, "Failed to switch to automatic mode after night period.");
            }
            nightResetScheduled.set(false); // Allow new resets to be scheduled
        }, resetTime);

        nightResetScheduled.set(true); // Mark reset as scheduled
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Scheduled automatic mode reset at " + dateFormat.format(resetTime));
    }



    private Date getResetTime() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, nightEndHour);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        // Subtract 5 minutes
        calendar.add(Calendar.MINUTE, -5);

        Date resetTime = calendar.getTime();
        if (resetTime.before(new Date())) {
            resetTime = new Date(resetTime.getTime() + 24 * 60 * 60 * 1000); // Add one day if already past
        }
        return resetTime;
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
                LogFilter.log(
                        LogFilter.LOG_LEVEL_INFO,
                        String.format(
                                "Monitoring RSOC during charging: Current RSOC = %d%%, Target RSOC = %d%%, LargeConsumerActive = %b",
                                currentRSOC, targetStateOfCharge, largeConsumerActive
                        )
                );

                // Calculate the remaining time to the end of the night period
                long currentTimeMillis = System.currentTimeMillis();
                long nightEndMillis = getNightEnd(getNightStart()).getTimeInMillis();
                long timeUntilNightEnd = nightEndMillis - currentTimeMillis;

                // 15 minutes before the end of the night period
                boolean isNearNightEnd = timeUntilNightEnd <= 15 * 60 * 1000 && timeUntilNightEnd > 0;

                // Check if RSOC has reached or exceeded the target
                if (currentRSOC >= targetStateOfCharge) {
                    LogFilter.log(
                            LogFilter.LOG_LEVEL_INFO,
                            String.format("Target RSOC (%d) reached. Stopping charging.", targetStateOfCharge)
                    );
                    boolean stopSuccess = batteryManagementService.setDynamicChargingPoint(0);

                    if (stopSuccess) {
                        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Charging successfully stopped as RSOC reached the target.");
                    } else {
                        LogFilter.log(LogFilter.LOG_LEVEL_ERROR, "Failed to stop charging despite reaching target RSOC.");
                    }

                    // Return to Automatic Mode
                    if (isWithinNighttimeWindow(currentTimeMillis) && isNearNightEnd) {
                        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Near the end of nighttime. Returning to Automatic Mode.");
                        batteryManagementService.resetToAutomaticMode();
                    } else if (!isWithinNighttimeWindow(currentTimeMillis)) {
                        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Daytime detected. Returning to Automatic Mode.");
                        batteryManagementService.resetToAutomaticMode();
                    }

                    // Cancel further checks for this period
                    return;
                }

                // Check if the current charging period has ended and schedule the next step
                if (new Date().after(endTime)) {
                    LogFilter.log(
                            LogFilter.LOG_LEVEL_INFO,
                            "Charging period ended. Checking for the next scheduled period."
                    );

                    Optional<ChargingSchedule> nextSchedule = getNextChargingSchedule();
                    if (nextSchedule.isPresent()) {
                        Date nextStartTime = new Date(nextSchedule.get().getStartTimestamp());
                        Date nextEndTime = new Date(nextSchedule.get().getEndTimestamp());

                        LogFilter.log(
                                LogFilter.LOG_LEVEL_INFO,
                                String.format("Next charging period scheduled: %s - %s.",
                                        dateFormat.format(nextStartTime),
                                        dateFormat.format(nextEndTime))
                        );

                        // Schedule the next charging period
                        taskScheduler.schedule(() -> executeChargingTask(nextStartTime, nextEndTime), nextStartTime);
                    } else {
                        LogFilter.log(
                                LogFilter.LOG_LEVEL_INFO,
                                "No further charging periods found. Stopping RSOC monitoring."
                        );
                    }
                    return;
                }

                // Check if we are near the end of nighttime and the target RSOC is not reached
                if (isWithinNighttimeWindow(currentTimeMillis) && isNearNightEnd) {
                    LogFilter.log(
                            LogFilter.LOG_LEVEL_INFO,
                            "Near the end of nighttime but RSOC target not reached. Continuing to charge."
                    );
                }

                // Reschedule the monitor task until the end of the charging period
                if (new Date().before(endTime)) {
                    taskScheduler.schedule(this, new Date(System.currentTimeMillis() + 5 * 60 * 1000)); // Check every 5 minutes
                } else {
                    LogFilter.log(
                            LogFilter.LOG_LEVEL_INFO,
                            "Charging period ended. Stopping RSOC monitoring."
                    );
                }
            }
        };

        // Initial scheduling of the monitor task
        taskScheduler.schedule(monitorTask, new Date(System.currentTimeMillis() + 5 * 60 * 1000)); // Start monitoring after 5 minutes
    }

    /**
     * Retrieves the next scheduled charging period, if available.
     *
     * @return An Optional containing the next ChargingSchedule, or empty if no further schedules exist.
     */
    private Optional<ChargingSchedule> getNextChargingSchedule() {
        long currentTime = System.currentTimeMillis();
        return chargingScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getStartTimestamp() > currentTime)
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .findFirst();
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
                .toList();

        boolean hasFutureSchedules = !activeSchedules.isEmpty();

        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format(
                        "Optimization check: RSOC=%d%%, LargeConsumer=%b, FutureSchedules=%b",
                        currentRSOC, hasLargeConsumer, hasFutureSchedules
                )
        );

        // Trigger optimization only if:
        return !hasFutureSchedules || hasLargeConsumer || currentRSOC < targetStateOfCharge;
    }

    public void cancelTask(long scheduleId) {
        ScheduledFuture<?> task = scheduledTasks.remove(scheduleId);
        if (task != null) {
            task.cancel(true);
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Cancelled scheduled task for schedule ID: " + scheduleId);
        }
    }

    public void schedulePlannedCharging() {
        List<ChargingSchedule> futureSchedules = chargingScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getStartTimestamp() > System.currentTimeMillis())
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .toList();

        for (ChargingSchedule schedule : futureSchedules) {
            long scheduleId = schedule.getId();
            Date startTime = new Date(schedule.getStartTimestamp());
            Date endTime = new Date(schedule.getEndTimestamp());

            // Check if a task already exists for this schedule
            ScheduledFuture<?> existingTask = scheduledTasks.get(scheduleId);

            // Cancel only if the existing task differs in timing
            if (existingTask != null && !isTaskUpToDate(existingTask, schedule)) {
                cancelTask(scheduleId);
            }

            // Schedule a new task only if no up-to-date task exists
            if (existingTask == null || !isTaskUpToDate(existingTask, schedule)) {
                ScheduledFuture<?> scheduledTask = taskScheduler.schedule(() -> {
                    LogFilter.log(
                            LogFilter.LOG_LEVEL_INFO,
                            String.format("Executing scheduled charging for period: %s - %s.",
                                    dateFormat.format(startTime),
                                    dateFormat.format(endTime))
                    );
                    executeChargingTask(startTime, endTime);
                }, startTime);

                // Save the task in the map
                scheduledTasks.put(scheduleId, scheduledTask);

                LogFilter.log(
                        LogFilter.LOG_LEVEL_INFO,
                        String.format("Scheduled charging task for: %s - %s.",
                                dateFormat.format(startTime),
                                dateFormat.format(endTime))
                );
            }
        }
    }

    private int calculateDynamicMaxChargingPeriods(int totalPeriods, double priceRange, int currentRSOC) {
        int baseMaxPeriods = currentRSOC < 50 ? 5 : 3; // Prioritize more periods for lower RSOC

        // Adjust based on price range
        if (priceRange < 0.5) {
            baseMaxPeriods += 2; // Allow more periods if price range is narrow
        } else if (priceRange > 2.0) {
            baseMaxPeriods -= 1; // Limit periods if price range is wide
        }

        // Adjust dynamically based on the number of total periods available
        if (totalPeriods <= 5) {
            baseMaxPeriods = Math.min(baseMaxPeriods + 2, totalPeriods); // Flexible with fewer periods
        } else if (totalPeriods > 10) {
            baseMaxPeriods = Math.max(baseMaxPeriods - 1, 2); // Stricter with many periods
        }

        // Ensure a minimum of 2 periods and a maximum of the total available periods
        int dynamicMaxPeriods = Math.max(2, baseMaxPeriods);
        dynamicMaxPeriods = Math.min(dynamicMaxPeriods, totalPeriods);

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Calculated dynamic max periods: %d (Total periods: %d, Price range: %.2f, Current RSOC: %d%%)",
                dynamicMaxPeriods, totalPeriods, priceRange, currentRSOC
        ));

        return dynamicMaxPeriods;
    }

    /**
     * Checks if the existing task matches the given schedule's timing based on its end timestamp.
     *
     * @param task The existing task.
     * @param schedule The charging schedule to compare against.
     * @return True if the task is up-to-date, false otherwise.
     */
    private boolean isTaskUpToDate(ScheduledFuture<?> task, ChargingSchedule schedule) {

        long actualEndTime = System.currentTimeMillis() + task.getDelay(TimeUnit.MILLISECONDS);
        long expectedEndTime = schedule.getEndTimestamp();
        long margin = 1000;
        return Math.abs(expectedEndTime - actualEndTime) <= margin;
    }

    /**
     * Optimizes daytime charging schedules dynamically based on market price analysis.
     * The method identifies optimal charging periods outside the nighttime window,
     * applies dynamic thresholds, and filters periods with cheaper alternatives to maximize cost efficiency.
     *
     * @return A list of validated and optimized charging schedules for daytime charging.
     */
    private List<ChargingSchedule> optimizeDaytimeCharging() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing daytime charging dynamically based on price analysis.");

        // Get the current system time
        long currentTime = System.currentTimeMillis();

        // Filter relevant periods that are outside the nighttime window and in the future
        List<MarketPrice> daytimePeriods = marketPriceRepository.findAll().stream()
                .filter(price -> !isWithinNighttimeWindow(price.getStartTimestamp())) // Exclude nighttime periods
                .filter(price -> price.getStartTimestamp() > currentTime) // Only future periods
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh)) // Sort by price
                .toList();

        // If no periods are found, log and return an empty list
        if (daytimePeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No daytime periods available for optimization.");
            return Collections.emptyList();
        }

        // Calculate dynamic thresholds and parameters for selecting optimal periods
        double threshold = calculateDynamicThreshold(daytimePeriods, priceFlexibilityThreshold);
        double maxAcceptablePrice = calculateMaxAcceptablePrice(
                batteryManagementService.getRelativeStateOfCharge(),
                maxAcceptableMarketPriceInCent
        );

        int dynamicMaxPeriods = calculateDynamicMaxChargingPeriods(
                daytimePeriods.size(),
                calculatePriceRange(daytimePeriods),
                batteryManagementService.getRelativeStateOfCharge()
        );

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Dynamic daytime threshold: %.2f cents/kWh, Max acceptable price: %.2f cents/kWh, Max periods: %d",
                threshold, maxAcceptablePrice, dynamicMaxPeriods));

        // Select periods based on calculated thresholds
        List<MarketPrice> selectedPeriods = daytimePeriods.stream()
                .filter(price -> price.getPriceInCentPerKWh() <= threshold)
                .filter(price -> price.getPriceInCentPerKWh() <= maxAcceptablePrice)
                .limit(dynamicMaxPeriods)
                .toList();

        // Filter out periods with cheaper future options
        List<MarketPrice> filteredPeriods = new ArrayList<>();
        for (MarketPrice period : selectedPeriods) {
            Optional<MarketPrice> cheaperFuture = findCheaperFuturePeriod(period, daytimePeriods);
            if (cheaperFuture.isEmpty()) {
                filteredPeriods.add(period);
            } else {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Skipping period %s - %s (%.2f cents/kWh). Found cheaper future period: %.2f cents/kWh.",
                        dateFormat.format(new Date(period.getStartTimestamp())),
                        dateFormat.format(new Date(period.getEndTimestamp())),
                        period.getPriceInCentPerKWh(),
                        cheaperFuture.get().getPriceInCentPerKWh()
                ));
            }
        }

        // Validate and optimize the filtered schedules
        Set<ChargingSchedule> validatedSchedules = validateSchedulesForCheaperOptions(
                collectAndOptimizeSchedules(filteredPeriods)
        );

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Validated %d daytime charging schedules.", validatedSchedules.size()));

        // Convert the validated schedules to a list and return
        return new ArrayList<>(validatedSchedules);
    }


    private Optional<MarketPrice> findCheaperFuturePeriod(MarketPrice currentPeriod, List<MarketPrice> allPeriods) {
        // Look for a future period that starts after the current period and has a lower price
        return allPeriods.stream()
                .filter(price -> price.getStartTimestamp() > currentPeriod.getEndTimestamp())
                .filter(price -> price.getPriceInCentPerKWh() < currentPeriod.getPriceInCentPerKWh())
                .findFirst();
    }

    private double calculatePriceRange(List<MarketPrice> periods) {
        // Calculate the price range between the minimum and maximum prices
        double minPrice = periods.stream().mapToDouble(MarketPrice::getPriceInCentPerKWh).min().orElse(0.0);
        double maxPrice = periods.stream().mapToDouble(MarketPrice::getPriceInCentPerKWh).max().orElse(0.0);
        return maxPrice - minPrice;
    }


    /**
     * Optimizes nighttime charging by dynamically selecting the most cost-effective periods.
     * The method prioritizes the cheapest periods within the nighttime window and restricts
     * the number of selected periods based on the Relative State of Charge (RSOC).
     *
     * Improvements:
     * 1. Stricter dynamic thresholds are applied to prioritize cheaper periods more effectively.
     * 2. Enhanced filtering to exclude any periods that don't strictly adhere to price thresholds.
     *
     * @return A list of optimized charging schedules for nighttime charging.
     */
    private List<ChargingSchedule> optimizeNighttimeCharging() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing nighttime charging dynamically for the current night.");

        long currentTime = System.currentTimeMillis();

        // Retrieve all market prices and filter for nighttime window and future periods
        List<MarketPrice> nighttimePeriods = marketPriceRepository.findAll().stream()
                .filter(price -> isWithinNighttimeWindow(price.getStartTimestamp())) // Check if within nighttime window
                .filter(price -> price.getStartTimestamp() > currentTime) // Exclude past periods
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh)) // Sort by price (ascending)
                .toList();

        if (nighttimePeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No nighttime periods available for optimization.");
            return Collections.emptyList();
        }

        // Calculate dynamic thresholds for price filtering
        double dynamicThreshold = calculateDynamicThreshold(nighttimePeriods, priceFlexibilityThreshold);
        double maxAcceptablePrice = calculateMaxAcceptablePrice(
                batteryManagementService.getRelativeStateOfCharge(),
                maxAcceptableMarketPriceInCent
        );

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Dynamic nighttime threshold: %.2f cents/kWh, Max acceptable price: %.2f cents/kWh",
                dynamicThreshold, maxAcceptablePrice
        ));

        // Select periods that meet the stricter thresholds and limit the count
        List<MarketPrice> selectedPeriods = nighttimePeriods.stream()
                .filter(price -> price.getPriceInCentPerKWh() <= dynamicThreshold) // Must meet dynamic threshold
                .filter(price -> price.getPriceInCentPerKWh() <= maxAcceptablePrice) // Must meet max acceptable price
                .limit(2)
                .toList();

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Selected %d periods for the current night based on RSOC and price analysis.", selectedPeriods.size()
        ));

        // Convert the selected periods into charging schedules
        return new ArrayList<>(collectAndOptimizeSchedules(selectedPeriods));
    }

    /**
     * Dynamically calculates the maximum number of periods to include based on the remaining RSOC.
     * Optionally, daytime-specific restrictions can be applied to limit charging periods further.
     *
     * @param currentRSOC The current relative state of charge.
     * @param isDaytime Indicates whether the calculation is for daytime charging.
     * @return The maximum number of periods to include.
     */
    private int calculateMaxPeriodsBasedOnRSOC(int currentRSOC, boolean isDaytime) {
        if (isDaytime) {
            if (currentRSOC >= 80) {
                return 0;
            } else if (currentRSOC >= 60) {
                return 1;
            } else {
                return 2;
            }
        } else {
            if (currentRSOC >= 80) {
                return 0;
            } else if (currentRSOC >= 60) {
                return 1;
            } else {
                return 2;
            }
        }
    }



    /**
     * Calculates the median price from a list of MarketPrice objects.
     */
    private double calculateMedianPrice(List<MarketPrice> prices) {
        List<Double> sortedPrices = prices.stream()
                .map(MarketPrice::getPriceInCentPerKWh)
                .sorted()
                .toList();
        int size = sortedPrices.size();
        return size % 2 == 0
                ? (sortedPrices.get(size / 2 - 1) + sortedPrices.get(size / 2)) / 2.0
                : sortedPrices.get(size / 2);
    }

    /**
     * Calculates a dynamic threshold for selecting charging periods based on market prices.
     * Considers the range and distribution of prices to adjust the threshold dynamically.
     *
     * @param prices          The list of market prices.
     * @param defaultThreshold The default flexibility threshold.
     * @return The calculated dynamic threshold.
     */
    private double calculateDynamicThreshold(List<MarketPrice> prices, double defaultThreshold) {
        // Calculate median price
        double medianPrice = calculateMedianPrice(prices);

        // Determine min, max, and average prices
        double minPrice = prices.stream()
                .mapToDouble(MarketPrice::getPriceInCentPerKWh)
                .min()
                .orElse(medianPrice);
        double maxPrice = prices.stream()
                .mapToDouble(MarketPrice::getPriceInCentPerKWh)
                .max()
                .orElse(medianPrice);
        double averagePrice = prices.stream()
                .mapToDouble(MarketPrice::getPriceInCentPerKWh)
                .average()
                .orElse(medianPrice);

        // Adjust threshold dynamically based on price range
        double range = maxPrice - minPrice;
        double dynamicThreshold = medianPrice + defaultThreshold;

        if (range < 0.5) { // Narrow price range -> increase flexibility
            dynamicThreshold += averagePrice * 0.1;
        } else if (range > 2.0) { // Wide price range -> reduce flexibility
            dynamicThreshold -= averagePrice * 0.1;
        }

        // Ensure threshold is not too restrictive
        dynamicThreshold = Math.max(dynamicThreshold, minPrice + 0.05);

        // Log calculation details
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Calculated dynamic threshold: %.2f (median: %.2f, min: %.2f, max: %.2f, avg: %.2f, range: %.2f)",
                dynamicThreshold, medianPrice, minPrice, maxPrice, averagePrice, range));

        return dynamicThreshold;
    }


    private double calculateMaxAcceptablePrice(double currentRSOC, double basePrice) {
        if (currentRSOC < targetStateOfCharge * 0.8) {
            return basePrice * 1.2;
        }
        return basePrice;
    }

    /**
     * Calculates a dynamic daytime threshold for charging based on the RSOC (Relative State of Charge) drop rate.
     * The threshold is adjusted dynamically to prioritize charging when the RSOC is depleting faster than expected.
     *
     * If there is insufficient historical data or invalid time differences, the method falls back to a default threshold.
     *
     * @return The calculated dynamic daytime threshold as a percentage of the target state of charge.
     */
    private int calculateDynamicDaytimeThreshold() {
        // Retrieve the RSOC history from the battery management service
        List<Map.Entry<Long, Integer>> history = batteryManagementService.getRsocHistory();

        // Ensure there is enough historical data to calculate a meaningful threshold
        if (history.size() < 2) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Not enough RSOC history data to calculate dynamic daytime threshold.");
            return targetStateOfCharge - 20; // Default fallback threshold
        }

        // Get the oldest and latest RSOC history entries
        Map.Entry<Long, Integer> oldest = history.get(0);
        Map.Entry<Long, Integer> latest = history.get(history.size() - 1);

        // Calculate the time difference between the oldest and latest entries in minutes
        long timeDifferenceInMinutes = (latest.getKey() - oldest.getKey()) / 60000;
        if (timeDifferenceInMinutes <= 0) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "Invalid time difference in RSOC history. Using default threshold.");
            return targetStateOfCharge - 20; // Fallback threshold for invalid data
        }

        // Calculate the difference in RSOC values over the recorded time period
        int rsocDifference = oldest.getValue() - latest.getValue();

        // Compute the RSOC drop rate in percentage per hour
        double rsocDropPerHour = rsocDifference / (timeDifferenceInMinutes / 60.0);
        LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                String.format("RSOC drop rate: %.2f%%/hour based on history.", rsocDropPerHour));

        // Dynamically adjust the threshold based on the drop rate
        int dynamicThreshold = (int) Math.max(targetStateOfCharge - (rsocDropPerHour * 2), targetStateOfCharge - 30);

        // Log the final calculated threshold
        LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                String.format("Dynamic daytime threshold calculated: %d%%", dynamicThreshold));

        return dynamicThreshold;
    }


    /**
     * Checks whether the given timestamp is within the nighttime window.
     * Uses caching to reduce redundant calculations within the cache duration.
     *
     * @param timestamp The timestamp to check (in milliseconds).
     * @return true if the timestamp is within the nighttime window, false otherwise.
     */
    private boolean isWithinNighttimeWindow(long timestamp) {
        // Use cached result if still valid
        if (cachedNighttimeWindow != null && System.currentTimeMillis() - cacheTimestamp < CACHE_DURATION_MS) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_DEBUG,
                    String.format("Using cached result for nighttime window check: %b", cachedNighttimeWindow)
            );
            return cachedNighttimeWindow;
        }

        // Calculate nighttime window dynamically
        Calendar nightStart = getNightStart();
        Calendar nightEnd = getNightEnd(nightStart);

        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format(
                        "Calculating nighttime window for timestamp %d (%s): Start=%s End=%s",
                        timestamp,
                        dateFormat.format(new Date(timestamp)),
                        dateFormat.format(nightStart.getTime()),
                        dateFormat.format(nightEnd.getTime())
                )
        );

        // Determine if the timestamp is within the window
        boolean isWithinWindow = isWithinTimeWindow(timestamp, nightStart.getTimeInMillis(), nightEnd.getTimeInMillis());

        // Update cache
        cachedNighttimeWindow = isWithinWindow;
        cacheTimestamp = System.currentTimeMillis();

        return isWithinWindow;
    }

    /**
     * Checks whether a timestamp is within a specified time window.
     *
     * @param timestamp The timestamp to check (in milliseconds).
     * @param start The start of the window (in milliseconds).
     * @param end The end of the window (in milliseconds).
     * @return true if the timestamp is within the window, false otherwise.
     */
    private boolean isWithinTimeWindow(long timestamp, long start, long end) {
        return timestamp >= start && timestamp < end;
    }


    private Calendar getNightEnd(Calendar nightStart) {
        // End of the night (always the next day after the night start)
        Calendar nightEnd = (Calendar) nightStart.clone();
        nightEnd.add(Calendar.DATE, 1);
        nightEnd.set(Calendar.HOUR_OF_DAY, nightEndHour);
        return nightEnd;
    }

    private Set<ChargingSchedule> validateSchedulesForCheaperOptions(Set<ChargingSchedule> schedules) {
        // Step 1: Sort schedules by price in ascending order
        List<ChargingSchedule> sortedSchedules = schedules.stream()
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice))
                .toList();

        Set<ChargingSchedule> validatedSchedules = new HashSet<>();

        // Step 2: Calculate minimum and maximum prices in the schedules
        double minPrice = sortedSchedules.stream()
                .mapToDouble(ChargingSchedule::getPrice)
                .min()
                .orElse(Double.MAX_VALUE);
        double maxPrice = sortedSchedules.stream()
                .mapToDouble(ChargingSchedule::getPrice)
                .max()
                .orElse(Double.MIN_VALUE);

        // Step 3: Calculate dynamic thresholds for price validation
        double priceToleranceFactor = calculateDynamicPriceTolerance(minPrice, maxPrice, sortedSchedules.size());
        double fallbackMargin = 0.50; // Minimum margin of 0.50 cents/kWh
        double priceThreshold = Math.min(
                Math.max(minPrice * (1 + priceToleranceFactor), minPrice + fallbackMargin),
                maxAcceptableMarketPriceInCent
        );

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Validation thresholds calculated: minPrice=%.2f, maxPrice=%.2f, priceThreshold=%.2f",
                minPrice, maxPrice, priceThreshold
        ));

        // Step 4: Define extended threshold and calculate maximum allowable charging periods
        double extendedThreshold = priceThreshold * 1.1; // 10% above the calculated threshold
        int maxChargingPeriods = calculateDynamicMaxChargingPeriods(
                sortedSchedules.size(),
                maxPrice - minPrice,
                batteryManagementService.getRelativeStateOfCharge()
        );

        // Step 5: Validate each schedule based on the calculated thresholds
        for (ChargingSchedule schedule : sortedSchedules) {
            boolean isWithinTolerance = schedule.getPrice() <= priceThreshold ||
                    (validatedSchedules.size() < maxChargingPeriods &&
                            schedule.getPrice() <= extendedThreshold);

            // Include top 2 best periods regardless of threshold if needed
            if (!isWithinTolerance && isOneOfBestPeriods(schedule, sortedSchedules)) {
                isWithinTolerance = true;
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Including schedule %s - %s (%.2f cents/kWh) as one of the best periods.",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp())),
                        schedule.getPrice()
                ));
            }

            if (isWithinTolerance) {
                validatedSchedules.add(schedule);
            } else {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Removed schedule %s - %s (%.2f cents/kWh) due to exceeding threshold %.2f.",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp())),
                        schedule.getPrice(),
                        extendedThreshold
                ));
            }
        }

        // Step 6: Fallback mechanism to ensure at least two schedules are retained
        if (validatedSchedules.isEmpty() && sortedSchedules.size() >= 2) {
            validatedSchedules.add(sortedSchedules.get(0));
            validatedSchedules.add(sortedSchedules.get(1));
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Fallback: Selected two cheapest periods due to empty validation result.");
        }

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Validated %d schedules after applying thresholds.", validatedSchedules.size()
        ));

        return validatedSchedules;
    }

    /**
     * Checks if the given schedule is one of the top best periods.
     *
     * @param schedule        The schedule to check.
     * @param sortedSchedules A list of schedules sorted by price.
     * @return True if the schedule is among the top best periods, false otherwise.
     */
    private boolean isOneOfBestPeriods(ChargingSchedule schedule, List<ChargingSchedule> sortedSchedules) {
        int maxBestPeriods = 2; // Number of besFt periods to allow regardless of threshold
        return sortedSchedules.indexOf(schedule) < maxBestPeriods;
    }

    /**
     * Finds a schedule to fill a gap between two periods, if available.
     *
     * @param gapStart The start timestamp of the gap.
     * @param gapEnd The end timestamp of the gap.
     * @return An optional ChargingSchedule to fill the gap.
     */
    private Optional<ChargingSchedule> findGapFillingSchedule(long gapStart, long gapEnd) {
        return chargingScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getStartTimestamp() >= gapStart && schedule.getEndTimestamp() <= gapEnd)
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice)) // Prefer cheaper schedules
                .findFirst();
    }

    /**
     * Calculates a dynamic price tolerance to determine the flexibility
     * in adjusting charging schedules based on market price characteristics.
     * The tolerance is influenced by:
     * - The range of market prices (variability between min and max prices).
     * - The total number of periods available for evaluation.
     * - Specific characteristics, such as exceptionally low minimum prices.
     *
     * @param minPrice The minimum price among the evaluated periods (cents/kWh).
     * @param maxPrice The maximum price among the evaluated periods (cents/kWh).
     * @param periodCount The number of periods being evaluated.
     * @return The calculated dynamic price tolerance as a multiplier (e.g., 1.5 = 150%).
     */
    private double calculateDynamicPriceTolerance(double minPrice, double maxPrice, int periodCount) {
        // Calculate the price range
        double priceRange = maxPrice - minPrice;

        // Initialize the base tolerance factor
        double baseTolerance = 1.5;

        // Adjust the base tolerance based on the price range
        if (priceRange < 0.5) {
            // Narrow price range → reduce tolerance (indicating less variability)
            baseTolerance -= 0.2;
        } else if (priceRange > 1.0) {
            // Wide price range → increase tolerance (allowing more flexibility)
            baseTolerance += 0.3;
        }

        // Further adjust tolerance based on the number of periods
        if (periodCount <= 5) {
            // Few periods → increase tolerance to accommodate limited options
            baseTolerance += 0.3;
        } else if (periodCount > 10) {
            // Many periods → reduce tolerance for stricter evaluation
            baseTolerance -= 0.2;
        }

        // Apply additional adjustments for exceptionally low prices
        if (minPrice < 0.1) {
            // Slightly increase tolerance for very low minimum prices
            baseTolerance += 0.1;
        }

        // Ensure the tolerance stays within reasonable bounds (optional)
        baseTolerance = Math.max(0.5, Math.min(baseTolerance, 2.0));

        // Return the dynamically calculated tolerance
        return baseTolerance;
    }

    private Calendar getNightStart() {
        Calendar now = Calendar.getInstance();

        // Start of the night (today or yesterday, depending on the time)
        Calendar nightStart = (Calendar) now.clone();
        nightStart.set(Calendar.HOUR_OF_DAY, nightStartHour);
        nightStart.set(Calendar.MINUTE, 0);
        nightStart.set(Calendar.SECOND, 0);
        nightStart.set(Calendar.MILLISECOND, 0);

        if (now.get(Calendar.HOUR_OF_DAY) < nightStartHour) {
            // If the current time is before the night start, set to yesterday
            nightStart.add(Calendar.DATE, -1);
        }
        return nightStart;
    }

    /**
     * Cleans up expired charging schedules and optimizes remaining schedules.
     */
    private void cleanUpExpiredSchedules() {
        long currentTime = System.currentTimeMillis();

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Starting cleanup of expired charging schedules...");

        // Load all schedules once
        List<ChargingSchedule> allSchedules = chargingScheduleRepository.findAll();

        // Step 1: Remove expired schedules
        List<ChargingSchedule> expiredSchedules = allSchedules.stream()
                .filter(schedule -> schedule.getEndTimestamp() < currentTime) // Expired schedules
                .toList();

        expiredSchedules.forEach(schedule -> {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Removing expired charging schedule: %s - %s.",
                    dateFormat.format(new Date(schedule.getStartTimestamp())),
                    dateFormat.format(new Date(schedule.getEndTimestamp()))));
            chargingScheduleRepository.delete(schedule);
        });

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format("Expired schedules cleanup completed. Removed: %d", expiredSchedules.size()));

        // Step 2: Evaluate and clean up schedules with cheaper future options
        List<ChargingSchedule> schedulesToEvaluate = allSchedules.stream()
                .filter(schedule -> schedule.getStartTimestamp() > currentTime)
                .toList();

        schedulesToEvaluate.parallelStream().forEach(currentSchedule -> {
            try {
                if (adjustForCheaperFutureSchedule(currentSchedule, schedulesToEvaluate)) {
                    cancelTask(currentSchedule.getId());
                    LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                            "Adjusted schedule due to cheaper future option: %s - %s (%.2f cents/kWh).",
                            dateFormat.format(new Date(currentSchedule.getStartTimestamp())),
                            dateFormat.format(new Date(currentSchedule.getEndTimestamp())),
                            currentSchedule.getPrice()));
                }
            } catch (Exception e) {
                LogFilter.log(LogFilter.LOG_LEVEL_ERROR, String.format(
                        "Failed to adjust schedule: %s - %s. Error: %s",
                        dateFormat.format(new Date(currentSchedule.getStartTimestamp())),
                        dateFormat.format(new Date(currentSchedule.getEndTimestamp())),
                        e.getMessage()));
            }
        });

        // Step 3: Optimize and retain future schedules
        List<ChargingSchedule> futureSchedules = schedulesToEvaluate.stream()
                .filter(schedule -> schedule.getEndTimestamp() > currentTime) // Keep future schedules
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice)) // Sort by price ascending
                .toList();

        if (!futureSchedules.isEmpty()) {
            futureSchedules = optimizeRemainingSchedules(futureSchedules); // Optimize the schedules

            futureSchedules.parallelStream().forEach(schedule -> {
                chargingScheduleRepository.save(schedule);
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Saved schedule: %s - %s at %.2f cents/kWh.",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp())),
                        schedule.getPrice()));
            });
        }

        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format("Remaining future schedules: %s",
                        futureSchedules.stream()
                                .map(schedule -> String.format("%s - %s (%.2f cents/kWh)",
                                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                                        dateFormat.format(new Date(schedule.getEndTimestamp())),
                                        schedule.getPrice()))
                                .collect(Collectors.joining(", "))
                )
        );

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format("Total schedules retained after cleanup: %d", futureSchedules.size()));
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Cleanup of charging schedules completed.");
    }


    /**
     * Calculates the maximum allowable idle hours before the next charging period
     * based on RSOC history and the current battery state.
     *
     * @param currentRSOC         Current relative state of charge (RSOC) in percentage.
     * @param targetRSOC          Target relative state of charge (RSOC) in percentage.
     * @param remainingCapacityWh Remaining capacity in watt-hours (Wh).
     * @return Maximum idle hours allowed.
     */
    private int calculateMaxIdleHours(int currentRSOC, int targetRSOC, int remainingCapacityWh) {
        List<Map.Entry<Long, Integer>> rsocHistory = batteryManagementService.getRsocHistory();

        if (rsocHistory.isEmpty() || rsocHistory.size() < 2) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "Insufficient RSOC history. Defaulting to a maximum idle time of 2 hours.");
            return 2; // Default fallback
        }

        double totalDrop = 0;
        double totalMinutes = 0;

        // Calculate the average RSOC drop per minute
        for (int i = 1; i < rsocHistory.size(); i++) {
            Map.Entry<Long, Integer> earlier = rsocHistory.get(i - 1);
            Map.Entry<Long, Integer> later = rsocHistory.get(i);

            long timeDifferenceMillis = later.getKey() - earlier.getKey();
            double timeDifferenceMinutes = timeDifferenceMillis / 60000.0;

            int rsocDifference = earlier.getValue() - later.getValue();

            if (timeDifferenceMinutes > 0) {
                totalDrop += rsocDifference;
                totalMinutes += timeDifferenceMinutes;
            }
        }

        double averageDropPerMinute = totalMinutes > 0 ? totalDrop / totalMinutes : 0;

        if (averageDropPerMinute <= 0) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No significant RSOC drop detected. Defaulting to a maximum idle time of 2 hours.");
            return 2;
        }

        // Calculate estimated hours until target RSOC
        double estimatedHoursUntilTarget = (targetRSOC - currentRSOC) / (averageDropPerMinute * 60);

        // Limit idle hours based on estimated hours and remaining capacity
        double estimatedHoursBasedOnCapacity = remainingCapacityWh / 1000.0; // Assume 1 kWh consumption per hour
        int maxIdleHours = (int) Math.min(Math.ceil(estimatedHoursUntilTarget), estimatedHoursBasedOnCapacity);

        LogFilter.log(LogFilter.LOG_LEVEL_DEBUG, String.format(
                "Average RSOC drop per minute: %.2f%%. Estimated hours until target RSOC: %.2f hours. " +
                        "Estimated hours based on capacity: %.2f hours. Calculated max idle hours: %d.",
                averageDropPerMinute, estimatedHoursUntilTarget, estimatedHoursBasedOnCapacity, maxIdleHours));

        return Math.max(1, maxIdleHours); // Ensure at least 1 hour
    }


    /**
     * Helper method to check if a cheaper future schedule exists and adjust the schedules accordingly.
     * Ensures critical RSOC (Relative State of Charge) levels and nighttime schedules are prioritized.
     *
     * @param currentSchedule The current charging schedule being evaluated.
     * @param schedulesToEvaluate The list of all available charging schedules to evaluate against.
     * @return True if a new schedule was created for a cheaper future option, false otherwise.
     */
    private boolean adjustForCheaperFutureSchedule(ChargingSchedule currentSchedule, List<ChargingSchedule> schedulesToEvaluate) {
        if (currentSchedule == null || schedulesToEvaluate == null || schedulesToEvaluate.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "Invalid input: Current schedule or schedules to evaluate is null/empty.");
            return false;
        }

        // Calculate dynamic tolerance based on all schedules and current RSOC
        double dynamicTolerance = calculateDynamicTolerance(schedulesToEvaluate, batteryManagementService.getRelativeStateOfCharge());

        if (dynamicTolerance < 0.0 || dynamicTolerance > 1.0) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN,
                    String.format("Dynamic tolerance out of bounds: %.2f. Adjusting to default 5%%.", dynamicTolerance));
            dynamicTolerance = 0.05; // Default to 5% if out of bounds
        }

        double priceTolerance = currentSchedule.getPrice() * dynamicTolerance;

        LogFilter.log(LogFilter.LOG_LEVEL_DEBUG, String.format(
                "Evaluating cheaper future schedules. Current schedule price: %.2f, Dynamic tolerance: %.2f, Price tolerance: %.2f",
                currentSchedule.getPrice(), dynamicTolerance, priceTolerance));

        // Find the cheapest schedule that starts after the current schedule
        Optional<ChargingSchedule> cheaperFuture = schedulesToEvaluate.stream()
                .filter(schedule -> schedule.getStartTimestamp() > currentSchedule.getStartTimestamp())
                .filter(schedule -> schedule.getPrice() + priceTolerance < currentSchedule.getPrice())
                .min(Comparator.comparingDouble(ChargingSchedule::getPrice));

        if (cheaperFuture.isPresent()) {
            ChargingSchedule cheaperSchedule = cheaperFuture.get();

            // Check if a schedule with the same start and end time already exists
            boolean scheduleExists = chargingScheduleRepository.existsByStartEndAndPrice(
                    cheaperSchedule.getStartTimestamp(),
                    cheaperSchedule.getEndTimestamp(),
                    cheaperSchedule.getPrice());

            if (scheduleExists) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Skipping creation of duplicate schedule for time period: %s - %s.",
                        dateFormat.format(new Date(cheaperSchedule.getStartTimestamp())),
                        dateFormat.format(new Date(cheaperSchedule.getEndTimestamp()))
                ));
                return false;
            }

            // Create a new schedule for the cheaper period
            ChargingSchedule newSchedule = new ChargingSchedule();
            newSchedule.setStartTimestamp(cheaperSchedule.getStartTimestamp());
            newSchedule.setEndTimestamp(cheaperSchedule.getEndTimestamp());
            newSchedule.setPrice(cheaperSchedule.getPrice());

            chargingScheduleRepository.save(newSchedule);

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Created new schedule for cheaper future option: %s - %s (price: %.2f cents/kWh).",
                    dateFormat.format(new Date(newSchedule.getStartTimestamp())),
                    dateFormat.format(new Date(newSchedule.getEndTimestamp())),
                    newSchedule.getPrice()
            ));

            return true;
        } else {
            LogFilter.log(LogFilter.LOG_LEVEL_DEBUG, "No cheaper future schedules found to adjust the current schedule.");
            return false;
        }
    }



    private double calculateDynamicTolerance(List<ChargingSchedule> allSchedules, double rsoc) {
        double averagePrice = allSchedules.stream()
                .mapToDouble(ChargingSchedule::getPrice)
                .average()
                .orElse(0.0);

        double standardDeviation = Math.sqrt(
                allSchedules.stream()
                        .mapToDouble(schedule -> Math.pow(schedule.getPrice() - averagePrice, 2))
                        .average()
                        .orElse(0.0)
        );

        int futureScheduleCount = (int) allSchedules.stream()
                .filter(schedule -> schedule.getStartTimestamp() > System.currentTimeMillis())
                .count();

        double baseTolerance = calculateBaseTolerance(averagePrice, rsoc);
        double volatilityFactor = calculateVolatilityFactor(standardDeviation, averagePrice, futureScheduleCount);

        return baseTolerance + volatilityFactor; // Combine base and dynamic factors
    }


    private double calculateBaseTolerance(double averagePrice, double rsoc) {
        double lowPriceThreshold = 10.0; // Example: low price threshold (cents/kWh)
        double highPriceThreshold = 30.0; // Example: high price threshold (cents/kWh)

        // Adjust baseline based on price levels
        if (averagePrice <= lowPriceThreshold) {
            return 0.07; // Higher tolerance for low prices (7%)
        } else if (averagePrice >= highPriceThreshold) {
            return 0.03; // Lower tolerance for high prices (3%)
        }

        // Adjust dynamically based on RSOC
        if (rsoc < 20.0) {
            return 0.02; // Prioritize immediate charging for low RSOC
        } else if (rsoc > 80.0) {
            return 0.06; // Flexible when RSOC is high
        }

        return 0.05; // Default baseline tolerance (5%)
    }

    private double calculateVolatilityFactor(double standardDeviation, double averagePrice, int futureScheduleCount) {
        double maxFactor = 0.03; // Maximum multiplier for high volatility
        double minFactor = 0.01; // Minimum multiplier for low volatility

        // Adjust based on price volatility
        double volatilityAdjustment = standardDeviation / averagePrice;

        // Adjust based on the number of future schedules
        double scheduleAdjustment = futureScheduleCount > 10 ? 0.005 : 0.0; // Add 0.5% if many future schedules exist

        double dynamicFactor = minFactor + (volatilityAdjustment * 0.02) + scheduleAdjustment;

        // Clamp the result to ensure it stays within bounds
        return Math.min(maxFactor, Math.max(minFactor, dynamicFactor));
    }

    private List<ChargingSchedule> optimizeRemainingSchedules(List<ChargingSchedule> schedules) {
        double minPrice = schedules.stream().mapToDouble(ChargingSchedule::getPrice).min().orElse(Double.MAX_VALUE);
        double rangePrice = schedules.stream().mapToDouble(ChargingSchedule::getPrice).max().orElse(Double.MIN_VALUE) - minPrice;

        return schedules.stream()
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice)) // Sort by price ascending
                .filter(schedule -> {
                    double priceDifference = schedule.getPrice() - minPrice;
                    double priceWeight = priceDifference / rangePrice;
                    return priceWeight < 0.3; // Only keep schedules within 30% of the cheapest
                })
                .collect(Collectors.toList());
    }

    /**
     * Executes a scheduled charging task.
     */
    private void executeChargingTask(Date startTime, Date endTime) {
        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format(
                        "Scheduled charging task started for period: %s - %s.",
                        dateFormat.format(startTime),
                        dateFormat.format(endTime)
                )
        );

        batteryManagementService.initCharging(false);
        scheduleRSOCMonitoring(endTime);
    }

    /**
     * Synchronizes the current charging schedules with the newly optimized schedules.
     * This method ensures the following:
     * <ul>
     *     <li>Outdated schedules are removed if they are replaced by cheaper alternatives.</li>
     *     <li>Validated and optimized schedules are saved to the repository.</li>
     *     <li>Unnecessary tasks associated with outdated schedules are canceled.</li>
     *     <li>Future tasks are re-scheduled based on the updated schedules.</li>
     * </ul>
     *
     * @param newSchedules The set of newly optimized charging schedules to be synchronized.
     */
    private void synchronizeSchedules(Set<ChargingSchedule> newSchedules) {
        long currentTime = System.currentTimeMillis();

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Starting synchronization of charging schedules...");

        // Step 1: Retrieve all existing future schedules from the repository
        List<ChargingSchedule> existingSchedules = chargingScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getEndTimestamp() > currentTime) // Only consider future schedules
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp)) // Sort by start time
                .toList();

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Loaded %d existing future schedules for comparison.", existingSchedules.size()));

        // Step 2: Validate the new schedules by removing redundant or invalid entries
        Set<ChargingSchedule> validatedNewSchedules = validateSchedulesForCheaperOptions(newSchedules);
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Validated %d new schedules for synchronization.", validatedNewSchedules.size()));

        // Step 3: Remove outdated schedules if they are replaced by cheaper alternatives
        for (ChargingSchedule existing : existingSchedules) {
            boolean hasCheaperAlternative = validatedNewSchedules.stream()
                    .anyMatch(newSchedule -> newSchedule.getStartTimestamp().equals(existing.getStartTimestamp())
                            && newSchedule.getEndTimestamp().equals(existing.getEndTimestamp())
                            && newSchedule.getPrice() < existing.getPrice());

            if (hasCheaperAlternative) {
                cancelTask(existing.getId());
                chargingScheduleRepository.delete(existing);
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Removed outdated schedule in favor of a cheaper alternative: %s - %s (%.2f cents/kWh).",
                        dateFormat.format(new Date(existing.getStartTimestamp())),
                        dateFormat.format(new Date(existing.getEndTimestamp())),
                        existing.getPrice()));
            }
        }

        // Step 4: Save the validated schedules to the repository, considering dynamic thresholds
        List<MarketPrice> marketPrices = marketPriceRepository.findAll(); // Retrieve market prices
        saveChargingSchedule(validatedNewSchedules, marketPrices);

        // Step 5: Re-schedule future tasks to reflect the updated schedules
        schedulePlannedCharging();

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Synchronization of charging schedules completed successfully.");
    }



    /**
     * Converts a list of market price periods into a set of optimized charging schedules.
     * Each period is transformed into a corresponding charging schedule, preserving
     * the start time, end time, and price attributes.
     *
     * @param periods A list of MarketPrice objects representing the charging periods.
     * @return A set of ChargingSchedule objects optimized from the given periods.
     */
    private Set<ChargingSchedule> collectAndOptimizeSchedules(List<MarketPrice> periods) {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Collecting and optimizing charging schedules from market price periods...");

        // Validate input and return an empty set if the list is null or empty
        if (periods == null || periods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No periods provided for schedule collection. Returning an empty set.");
            return Collections.emptySet();
        }

        // Initialize the set to store optimized schedules
        Set<ChargingSchedule> optimizedSchedules = new HashSet<>();

        // Convert each market price period into a charging schedule
        for (MarketPrice period : periods) {
            if (period.getStartTimestamp() >= period.getEndTimestamp()) {
                LogFilter.log(LogFilter.LOG_LEVEL_WARN, String.format(
                        "Invalid period detected: Start=%d, End=%d. Skipping.",
                        period.getStartTimestamp(), period.getEndTimestamp()));
                continue; // Skip invalid periods
            }

            ChargingSchedule schedule = new ChargingSchedule();
            schedule.setStartTimestamp(period.getStartTimestamp());
            schedule.setEndTimestamp(period.getEndTimestamp());
            schedule.setPrice(period.getPriceInCentPerKWh());

            // Add the schedule to the optimized set
            if (optimizedSchedules.add(schedule)) {
                LogFilter.log(LogFilter.LOG_LEVEL_DEBUG, String.format(
                        "Added schedule: Start=%s, End=%s, Price=%.2f cents/kWh.",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp())),
                        schedule.getPrice()));
            } else {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Duplicate schedule detected: Start=%s, End=%s. Skipping.",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp()))));
            }
        }

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Collected and optimized %d charging schedules.", optimizedSchedules.size()));
        return optimizedSchedules;
    }

    /**
     * Saves the selected charging periods to the database, avoiding duplicate, expired, and overpriced entries,
     * and ensuring validation against dynamic thresholds.
     *
     * @param schedules The set of charging schedules to be saved.
     * @param marketPrices The list of market prices used for dynamic threshold validation.
     */
    private void saveChargingSchedule(Set<ChargingSchedule> schedules, List<MarketPrice> marketPrices) {
        long currentTime = System.currentTimeMillis();

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Attempting to save %d charging schedules to the database.", schedules.size()));

        // Maintain a set of validated schedules to avoid overlaps
        Set<ChargingSchedule> validatedSchedules = new HashSet<>();

        for (ChargingSchedule schedule : schedules) {
            // Step 1: Validate the schedule to ensure it meets all criteria
            if (!isValidSchedule(schedule, currentTime, validatedSchedules, marketPrices)) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Skipping invalid schedule: %s - %s at %.2f cents/kWh.",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp())),
                        schedule.getPrice()));
                continue;
            }

            // Step 2: Check for duplicates in the repository
            boolean exists = chargingScheduleRepository.existsByStartEndAndPrice(
                    schedule.getStartTimestamp(),
                    schedule.getEndTimestamp(),
                    schedule.getPrice()
            );

            if (exists) {
                LogFilter.log(
                        LogFilter.LOG_LEVEL_INFO,
                        String.format(
                                "Skipping duplicate schedule: %s - %s at %.2f cents/kWh.",
                                dateFormat.format(new Date(schedule.getStartTimestamp())),
                                dateFormat.format(new Date(schedule.getEndTimestamp())),
                                schedule.getPrice()
                        )
                );
                continue;
            }

            // Step 3: Save the valid schedule to the repository
            try {
                chargingScheduleRepository.save(schedule);
                validatedSchedules.add(schedule); // Add to validated set after saving
                LogFilter.log(
                        LogFilter.LOG_LEVEL_INFO,
                        String.format(
                                "Successfully saved schedule: %s - %s at %.2f cents/kWh.",
                                dateFormat.format(new Date(schedule.getStartTimestamp())),
                                dateFormat.format(new Date(schedule.getEndTimestamp())),
                                schedule.getPrice()
                        )
                );
            } catch (Exception e) {
                LogFilter.log(
                        LogFilter.LOG_LEVEL_ERROR,
                        String.format(
                                "Failed to save schedule: %s - %s at %.2f cents/kWh. Error: %s",
                                dateFormat.format(new Date(schedule.getStartTimestamp())),
                                dateFormat.format(new Date(schedule.getEndTimestamp())),
                                schedule.getPrice(),
                                e.getMessage()
                        )
                );
            }
        }

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Completed saving all valid schedules to the database.");
    }
    /**
     * Validates whether a charging schedule meets basic criteria (e.g., not expired, not overpriced).
     * Also ensures it adheres to dynamic price thresholds calculated based on market prices.
     *
     * @param schedule       The schedule to validate.
     * @param currentTime    The current system time in milliseconds.
     * @param validatedSchedules A set of already validated schedules for overlap checks.
     * @param marketPrices   The list of market prices for dynamic threshold calculation.
     * @return True if the schedule is valid, false otherwise.
     */
    private boolean isValidSchedule(
            ChargingSchedule schedule,
            long currentTime,
            Set<ChargingSchedule> validatedSchedules,
            List<MarketPrice> marketPrices
    ) {
        // Check if the schedule is expired
        if (schedule.getEndTimestamp() < currentTime) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format(
                            "Skipping expired schedule: %s - %s (%.2f cents/kWh).",
                            dateFormat.format(new Date(schedule.getStartTimestamp())),
                            dateFormat.format(new Date(schedule.getEndTimestamp())),
                            schedule.getPrice()
                    )
            );
            return false;
        }

        // Check if the schedule is overpriced
        if (schedule.getPrice() > maxAcceptableMarketPriceInCent) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format(
                            "Skipping overpriced schedule: %s - %s at %.2f cents/kWh (max acceptable: %d cents/kWh).",
                            dateFormat.format(new Date(schedule.getStartTimestamp())),
                            dateFormat.format(new Date(schedule.getEndTimestamp())),
                            schedule.getPrice(),
                            maxAcceptableMarketPriceInCent
                    )
            );
            return false;
        }

        // Check for overlapping schedules
        boolean overlaps = validatedSchedules.stream().anyMatch(validated ->
                schedule.getStartTimestamp() < validated.getEndTimestamp() &&
                        schedule.getEndTimestamp() > validated.getStartTimestamp());
        if (overlaps) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format(
                            "Skipping overlapping schedule: %s - %s (%.2f cents/kWh).",
                            dateFormat.format(new Date(schedule.getStartTimestamp())),
                            dateFormat.format(new Date(schedule.getEndTimestamp())),
                            schedule.getPrice()
                    )
            );
            return false;
        }

        // Validate against dynamic price thresholds
        double dynamicThreshold = calculateDynamicThreshold(marketPrices, priceFlexibilityThreshold);
        if (schedule.getPrice() > dynamicThreshold) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format(
                            "Skipping schedule exceeding dynamic threshold: %s - %s at %.2f cents/kWh (threshold: %.2f cents/kWh).",
                            dateFormat.format(new Date(schedule.getStartTimestamp())),
                            dateFormat.format(new Date(schedule.getEndTimestamp())),
                            schedule.getPrice(),
                            dynamicThreshold
                    )
            );
            return false;
        }

        // Schedule meets all validation criteria
        return true;
    }
    /**
     * Retrieves and sorts all existing charging schedules.
     *
     * @return A sorted list of existing charging schedules.
     */
    public List<ChargingSchedule> getSortedChargingSchedules() {
        return chargingScheduleRepository.findAll().stream()
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .toList();
    }

}
