package de.zeus.power.service;

import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.event.MarketPricesUpdatedEvent;
import de.zeus.power.repository.ChargingScheduleRepository;
import de.zeus.power.repository.MarketPriceRepository;
import de.zeus.power.config.LogFilter;
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

    @Value("${daytime.preferred.start:10}")
    private int preferredStartHour;

    @Value("${daytime.preferred.end:15}")
    private int preferredEndHour;

    @Value("${charging.schedule.max.periods:2}")
    private int maxChargingPeriods;

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

        Set<ChargingSchedule> optimizedSchedules = new HashSet<>();
        optimizedSchedules.addAll(optimizeNighttimeCharging());
        optimizedSchedules.addAll(optimizeTransitionPeriods());
        optimizedSchedules.addAll(optimizeDaytimeCharging());

        synchronizeSchedules(optimizedSchedules);

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Market price update handled and schedules synchronized.");
    }

    @Scheduled(cron = "0 0 * * * ?") // Every full hour
    public void scheduledOptimizeChargingSchedule() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Scheduled optimization of charging schedule triggered.");
        if (shouldOptimize()) {
            Set<ChargingSchedule> optimizedSchedules = new HashSet<>();
            optimizedSchedules.addAll(optimizeNighttimeCharging());
            optimizedSchedules.addAll(optimizeTransitionPeriods());
            optimizedSchedules.addAll(optimizeDaytimeCharging());

            synchronizeSchedules(optimizedSchedules);
        } else {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "No significant changes detected. Skipping optimization.");
        }
        cleanUpExpiredSchedules();
    }


    @Scheduled(fixedRateString = "${battery.automatic.mode.check.interval:300000}") // Every 5 minutes
    public void checkAndResetToAutomaticMode() {
        if (batteryManagementService.isBatteryNotConfigured()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Battery not configured. Skipping automatic mode check.");
            return;
        }

        int currentRSOC = batteryManagementService.getRelativeStateOfCharge();
        boolean isNightTime = isWithinNighttimeWindow(System.currentTimeMillis());
        boolean largeConsumerActive = batteryManagementService.isLargeConsumerActive();

        // Check if forced charging is active
        if (batteryManagementService.isForcedChargingActive()) {
            if (currentRSOC >= targetStateOfCharge) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                        String.format("Target RSOC (%d%%) reached during forced charging. Disabling forced charging mode.",
                                targetStateOfCharge));
                batteryManagementService.setForcedChargingActive(false);
                batteryManagementService.resetToAutomaticMode();
            } else {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Forced charging is active. Skipping automatic mode reset.");
                return;
            }
        }

        // Delegate dynamic adjustment to the BatteryManagementService
        boolean success = batteryManagementService.adjustChargingPointDynamically(currentRSOC, isNightTime, largeConsumerActive);
        if (!success) {
            LogFilter.log(LogFilter.LOG_LEVEL_ERROR, "Failed to adjust charging point dynamically.");
        }

        // Reset to automatic mode at the end of nighttime
        if (isNightTime && largeConsumerActive) {
            scheduleEndOfNightReset();
        } else if (!isNightTime) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                    "Outside nighttime. Returning to Automatic Mode.");
            batteryManagementService.resetToAutomaticMode();
        }
    }


    private void scheduleEndOfNightReset() {
        if (nightResetScheduled.get()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Night reset already scheduled. Skipping duplicate scheduling.");
            return;
        }

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

        taskScheduler.schedule(() -> {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Night period ended. Switching back to automatic mode.");
            boolean resetSuccessful = batteryManagementService.resetToAutomaticMode();
            if (resetSuccessful) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Successfully returned to automatic mode after night period.");
            } else {
                LogFilter.log(LogFilter.LOG_LEVEL_ERROR, "Failed to switch to automatic mode after night period.");
            }
            nightResetScheduled.set(false); // Reset the flag after execution
        }, resetTime);

        nightResetScheduled.set(true); // Set the flag
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Scheduled automatic mode reset at " + dateFormat.format(resetTime));
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
                    if (!isWithinNighttimeWindow(System.currentTimeMillis())) {
                        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Not nighttime and Large Consumer detected. Returning to Automatic Mode.");
                        batteryManagementService.resetToAutomaticMode();
                    }

                    // Cancel further checks for this period
                    return;
                }

                // Check if we are outside the night period and a large consumer is active
                if (!isWithinNighttimeWindow(System.currentTimeMillis()) && largeConsumerActive) {
                    LogFilter.log(
                            LogFilter.LOG_LEVEL_INFO,
                            "Large consumer detected outside nighttime. Stopping charging and returning to Automatic Mode."
                    );
                    batteryManagementService.resetToAutomaticMode();
                    return;
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


    private Set<ChargingSchedule> validateSchedulesForCheaperOptions(Set<ChargingSchedule> schedules) {
        List<ChargingSchedule> sortedSchedules = schedules.stream()
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice))
                .collect(Collectors.toList());

        Set<ChargingSchedule> validatedSchedules = new HashSet<>();

        for (ChargingSchedule schedule : sortedSchedules) {
            boolean hasCheaperAlternative = schedules.stream()
                    .anyMatch(s -> s.getStartTimestamp() >= schedule.getStartTimestamp()
                            && s.getEndTimestamp() <= schedule.getEndTimestamp()
                            && s.getPrice() < schedule.getPrice());

            if (!hasCheaperAlternative) {
                validatedSchedules.add(schedule);
            } else {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Removed schedule %s - %s (%.2f cents/kWh) due to cheaper alternative.",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp())),
                        schedule.getPrice()));
            }
        }

        return validatedSchedules;
    }

    public void schedulePlannedCharging() {
        List<ChargingSchedule> futureSchedules = chargingScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getStartTimestamp() > System.currentTimeMillis())
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());

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

    /**
     * Checks if the existing task matches the given schedule's timing.
     *
     * @param task The existing task.
     * @param schedule The charging schedule to compare against.
     * @return True if the task is up-to-date, false otherwise.
     */
    private boolean isTaskUpToDate(ScheduledFuture<?> task, ChargingSchedule schedule) {
        // Calculate the expected delay for the task based on the schedule's start timestamp
        long expectedDelay = schedule.getStartTimestamp() - System.currentTimeMillis();

        // Check if the task's delay matches the expected delay within a reasonable margin
        long actualDelay = task.getDelay(TimeUnit.MILLISECONDS);
        long margin = 1000; // 1 second margin for timing discrepancies

        return Math.abs(expectedDelay - actualDelay) <= margin;
    }


    /**
     * Generates a unique signature for a schedule based on its start time, end time, and price.
     * This ensures we can compare schedules based on their key attributes.
     *
     * @param schedule The ChargingSchedule to generate a signature for.
     * @return A unique string signature in the format "start-end-price".
     */
    private String createScheduleSignature(ChargingSchedule schedule) {
        return String.format("%d-%d-%.2f",
                schedule.getStartTimestamp(),
                schedule.getEndTimestamp(),
                schedule.getPrice());
    }

    /**
     * Checks if the existing task matches the given schedule's signature.
     *
     * @param taskId The ID of the existing task.
     * @param schedule The charging schedule to compare against.
     * @return True if the task matches the schedule signature, false otherwise.
     */
    private boolean isTaskUpToDate(long taskId, ChargingSchedule schedule) {
        String existingSignature = scheduledTasks.containsKey(taskId)
                ? createScheduleSignature(schedule)
                : null;
        String currentSignature = String.format("%d-%d-%.2f",
                schedule.getStartTimestamp(),
                schedule.getEndTimestamp(),
                schedule.getPrice());
        return existingSignature != null && existingSignature.equals(currentSignature);
    }




    private List<ChargingSchedule> optimizeDaytimeCharging() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing daytime charging dynamically based on price analysis.");

        long currentTime = System.currentTimeMillis();
        Calendar dayStart = Calendar.getInstance();
        dayStart.set(Calendar.HOUR_OF_DAY, preferredStartHour);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);

        Calendar dayEnd = (Calendar) dayStart.clone();
        dayEnd.set(Calendar.HOUR_OF_DAY, preferredEndHour);

        List<MarketPrice> daytimePeriods = marketPriceRepository.findAll().stream()
                .filter(price -> price.getStartTimestamp() >= dayStart.getTimeInMillis())
                .filter(price -> price.getStartTimestamp() < dayEnd.getTimeInMillis())
                .filter(price -> price.getStartTimestamp() > currentTime)
                .collect(Collectors.toList());

        if (daytimePeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No daytime periods available for optimization.");
            return Collections.emptyList();
        }

        // calculate Median & percentileThreshold
        double medianPrice = calculateMedianPrice(daytimePeriods);
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format("Daytime Periods Periods median price: %.2f cents/kWh", medianPrice));
        double percentileThreshold = calculatePercentileThreshold(daytimePeriods, -1);

        // Select periods below the media
        List<MarketPrice> selectedPeriods = daytimePeriods.stream()
                .filter(price -> price.getPriceInCentPerKWh() <= percentileThreshold
                        || price.getPriceInCentPerKWh() <= medianPrice)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(maxChargingPeriods)
                .collect(Collectors.toList());

        return new ArrayList<>(collectAndOptimizeSchedules(selectedPeriods));
    }


    private List<ChargingSchedule> optimizeNighttimeCharging() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing nighttime charging dynamically based on price analysis.");

        long currentTime = System.currentTimeMillis();
        Calendar nightStart = Calendar.getInstance();
        nightStart.set(Calendar.HOUR_OF_DAY, nightStartHour);
        nightStart.set(Calendar.MINUTE, 0);
        nightStart.set(Calendar.SECOND, 0);
        nightStart.set(Calendar.MILLISECOND, 0);

        Calendar nightEnd = (Calendar) nightStart.clone();
        nightEnd.add(Calendar.DATE, 1);
        nightEnd.set(Calendar.HOUR_OF_DAY, nightEndHour);

        List<MarketPrice> nighttimePeriods = marketPriceRepository.findAll().stream()
                .filter(price -> price.getStartTimestamp() >= nightStart.getTimeInMillis())
                .filter(price -> price.getStartTimestamp() < nightEnd.getTimeInMillis())
                .filter(price -> price.getStartTimestamp() > currentTime)
                .collect(Collectors.toList());

        if (nighttimePeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No nighttime periods available for optimization.");
            return Collections.emptyList();
        }

        double medianPrice = calculateMedianPrice(nighttimePeriods);
        double percentileThreshold = calculatePercentileThreshold(nighttimePeriods, -1);

        // Select periods below the median or percentile threshold
        List<MarketPrice> selectedPeriods = nighttimePeriods.stream()
                .filter(price -> price.getPriceInCentPerKWh() <= percentileThreshold
                        || price.getPriceInCentPerKWh() <= medianPrice)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .collect(Collectors.toList());

        // **Check for cheaper periods within the same time range**
        List<MarketPrice> optimizedPeriods = new ArrayList<>();
        for (MarketPrice period : selectedPeriods) {
            boolean hasCheaperOption = nighttimePeriods.stream()
                    .filter(p -> p.getStartTimestamp() >= period.getStartTimestamp()
                            && p.getEndTimestamp() <= period.getEndTimestamp())
                    .anyMatch(p -> p.getPriceInCentPerKWh() < period.getPriceInCentPerKWh());

            if (!hasCheaperOption) {
                optimizedPeriods.add(period);
            } else {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Excluded period %s - %s (%.2f cents/kWh) due to cheaper alternative.",
                        dateFormat.format(new Date(period.getStartTimestamp())),
                        dateFormat.format(new Date(period.getEndTimestamp())),
                        period.getPriceInCentPerKWh()));
            }
        }

        return new ArrayList<>(collectAndOptimizeSchedules(optimizedPeriods));
    }

    /**
     * Calculates the median price from a list of MarketPrice objects.
     */
    private double calculateMedianPrice(List<MarketPrice> prices) {
        List<Double> sortedPrices = prices.stream()
                .map(MarketPrice::getPriceInCentPerKWh)
                .sorted()
                .collect(Collectors.toList());
        int size = sortedPrices.size();
        return size % 2 == 0
                ? (sortedPrices.get(size / 2 - 1) + sortedPrices.get(size / 2)) / 2.0
                : sortedPrices.get(size / 2);
    }

    /**
     * Calculates the threshold for a given or dynamically calculated percentile.
     *
     * @param prices     List of MarketPrice objects
     * @param percentile Desired percentile (e.g., 20 for the 20th percentile, -1 for dynamic calculation)
     * @return Threshold value for the given or dynamically calculated percentile
     */
    private double calculatePercentileThreshold(List<MarketPrice> prices, int percentile) {
        if (prices.isEmpty()) {
            return Double.MAX_VALUE; // No data, return a very high threshold
        }

        // If percentile is -1, calculate it dynamically
        if (percentile == -1) {
            return calculateDynamicPercentile(prices);
        }

        // Static percentile calculation
        List<Double> sortedPrices = prices.stream()
                .map(MarketPrice::getPriceInCentPerKWh) // Extract the price values
                .sorted() // Sort the prices in ascending order
                .collect(Collectors.toList());
        int index = (int) Math.ceil(percentile / 100.0 * sortedPrices.size()) - 1;
        return sortedPrices.get(Math.max(0, index)); // Return the value at the percentile position
    }

    /**
     * Dynamically calculates the percentile threshold based on price distribution.
     *
     * @param prices List of MarketPrice objects
     * @return Threshold value for the dynamically calculated percentile
     */
    private double calculateDynamicPercentile(List<MarketPrice> prices) {
        // Calculate the mean and standard deviation
        double averagePrice = prices.stream().mapToDouble(MarketPrice::getPriceInCentPerKWh).average().orElse(Double.MAX_VALUE);
        double standardDeviation = Math.sqrt(
                prices.stream()
                        .mapToDouble(price -> Math.pow(price.getPriceInCentPerKWh() - averagePrice, 2))
                        .average()
                        .orElse(0)
        );

        // Determine the dynamic percentile based on price distribution
        // Example logic: Aggressive for very low average prices, conservative otherwise
        double dynamicPercentile = averagePrice < 5 ? 10 : (averagePrice < 10 ? 20 : 30);

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Dynamic percentile calculated: %.2f%% (average=%.2f, stdDev=%.2f)",
                dynamicPercentile, averagePrice, standardDeviation
        ));

        // Calculate threshold for the dynamic percentile
        List<Double> sortedPrices = prices.stream()
                .map(MarketPrice::getPriceInCentPerKWh)
                .sorted()
                .collect(Collectors.toList());
        int index = (int) Math.ceil(dynamicPercentile / 100.0 * sortedPrices.size()) - 1;
        return sortedPrices.get(Math.max(0, index));
    }


    private boolean isWithinNighttimeWindow(long timestamp) {
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

        // End of the night (always the next day after the night start)
        Calendar nightEnd = (Calendar) nightStart.clone();
        nightEnd.add(Calendar.DATE, 1); // Ende der Nacht ist immer am nächsten Tag
        nightEnd.set(Calendar.HOUR_OF_DAY, nightEndHour);

        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format(
                        "Checking if timestamp %d (%s) is within nighttime window: Start=%s End=%s",
                        timestamp,
                        dateFormat.format(new Date(timestamp)),
                        dateFormat.format(nightStart.getTime()),
                        dateFormat.format(nightEnd.getTime())
                )
        );


        return timestamp >= nightStart.getTimeInMillis() && timestamp < nightEnd.getTimeInMillis();
    }

    /**
     * Cleans up expired charging schedules and optimizes remaining schedules.
     */
    private void cleanUpExpiredSchedules() {
        long currentTime = System.currentTimeMillis();

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Starting cleanup of expired charging schedules...");

        // Step 1: Remove expired schedules
        List<ChargingSchedule> expiredSchedules = chargingScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getEndTimestamp() < currentTime) // Expired schedules
                .collect(Collectors.toList());

        expiredSchedules.forEach(schedule -> {
            logger.info("Removing expired charging schedule: {} - {}.",
                    dateFormat.format(new Date(schedule.getStartTimestamp())),
                    dateFormat.format(new Date(schedule.getEndTimestamp())));
            chargingScheduleRepository.delete(schedule);
        });

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format("Expired schedules cleanup completed. Removed: %d", expiredSchedules.size()));

        // Step 2: Evaluate and clean up daytime schedules before nighttime starts
        long twoHoursBeforeNight = calculateTwoHoursBeforeNight();
        List<ChargingSchedule> daySchedulesToEvaluate = chargingScheduleRepository.findAll().stream()
                .filter(schedule -> !isWithinNighttimeWindow(schedule.getStartTimestamp())) // Only daytime schedules
                .filter(schedule -> schedule.getStartTimestamp() < twoHoursBeforeNight) // Starts before nighttime
                .collect(Collectors.toList());

        daySchedulesToEvaluate.forEach(schedule -> {
            if (hasCheaperNightSchedule(schedule)) {
                logger.info("Removing daytime schedule due to cheaper nighttime options: {} - {}.",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp())));
                chargingScheduleRepository.delete(schedule);
            }
        });

        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format("Daytime schedule evaluation completed. Evaluated: %d", daySchedulesToEvaluate.size())
        );

        // Step 3: Keep only future schedules
        List<ChargingSchedule> futureSchedules = chargingScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getEndTimestamp() > currentTime) // Keep future schedules
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice)) // Sort by price ascending
                .collect(Collectors.toList());

// Log retained future schedules
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

        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                "Cleanup of charging schedules completed."
        );
    }

    private long calculateTwoHoursBeforeNight() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, nightStartHour);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        return calendar.getTimeInMillis() - (2 * 60 * 60 * 1000);
    }

    private boolean hasCheaperNightSchedule(ChargingSchedule daySchedule) {
        return chargingScheduleRepository.findAll().stream()
                .filter(schedule -> isWithinNighttimeWindow(schedule.getStartTimestamp()))
                .anyMatch(nightSchedule -> nightSchedule.getPrice() < daySchedule.getPrice());
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

    private List<ChargingSchedule> optimizeTransitionPeriods() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing transition periods between night and day.");

        Calendar nightEnd = Calendar.getInstance();
        nightEnd.set(Calendar.HOUR_OF_DAY, nightEndHour);
        nightEnd.set(Calendar.MINUTE, 0);
        nightEnd.set(Calendar.SECOND, 0);
        nightEnd.set(Calendar.MILLISECOND, 0);

        Calendar dayStart = Calendar.getInstance();
        dayStart.set(Calendar.HOUR_OF_DAY, preferredStartHour);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);

        List<MarketPrice> transitionPeriods = marketPriceRepository.findAll().stream()
                .filter(price -> price.getStartTimestamp() >= nightEnd.getTimeInMillis())
                .filter(price -> price.getEndTimestamp() <= dayStart.getTimeInMillis())
                .collect(Collectors.toList());

        if (transitionPeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No transition periods available for optimization.");
            return Collections.emptyList();
        }

        // calculate Median & percentileThreshold
        double medianPrice = calculateMedianPrice(transitionPeriods);
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format("Daytime Periods Periods median price: %.2f cents/kWh", medianPrice));
        double percentileThreshold = calculatePercentileThreshold(transitionPeriods, -1);

        // Select periods below the media
        List<MarketPrice> selectedPeriods = transitionPeriods.stream()
                .filter(price -> price.getPriceInCentPerKWh() <= percentileThreshold
                        || price.getPriceInCentPerKWh() <= medianPrice)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(maxChargingPeriods)
                .collect(Collectors.toList());

        return new ArrayList<>(collectAndOptimizeSchedules(selectedPeriods));
    }

    /**
     * Synchronizes the current charging schedules with the newly optimized schedules.
     * Ensures outdated schedules are removed, unneeded tasks are canceled,
     * and only new or updated schedules are added and scheduled.
     *
     * @param newSchedules The set of newly optimized charging schedules.
     */
    private void synchronizeSchedules(Set<ChargingSchedule> newSchedules) {
        long currentTime = System.currentTimeMillis();

        // Load existing future schedules and sort them by start time
        List<ChargingSchedule> existingSchedules = chargingScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getEndTimestamp() > currentTime) // Only future schedules
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp)) // Sort by start time
                .collect(Collectors.toList());

        // Validate new schedules (retain only cheaper alternatives)
        Set<ChargingSchedule> validatedNewSchedules = validateSchedulesForCheaperOptions(newSchedules);

        // Remove outdated schedules if they are replaced by cheaper alternatives
        for (ChargingSchedule existing : existingSchedules) {
            boolean hasCheaperAlternative = validatedNewSchedules.stream()
                    .anyMatch(newSchedule -> newSchedule.getStartTimestamp() == existing.getStartTimestamp()
                            && newSchedule.getEndTimestamp() == existing.getEndTimestamp()
                            && newSchedule.getPrice() < existing.getPrice());
            if (hasCheaperAlternative) {
                cancelTask(existing.getId());
                chargingScheduleRepository.delete(existing);
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Removed outdated schedule in favor of cheaper alternative: %s - %s (%.2f cents/kWh).",
                        dateFormat.format(new Date(existing.getStartTimestamp())),
                        dateFormat.format(new Date(existing.getEndTimestamp())),
                        existing.getPrice()));
            }
        }

        // Save new validated schedules using saveChargingSchedule
        saveChargingSchedule(validatedNewSchedules);

        // Re-schedule all validated future tasks
        schedulePlannedCharging();
    }


    private Set<ChargingSchedule> collectAndOptimizeSchedules(List<MarketPrice> periods) {
        Set<ChargingSchedule> optimizedSchedules = new HashSet<>();

        for (MarketPrice period : periods) {
            ChargingSchedule schedule = new ChargingSchedule();
            schedule.setStartTimestamp(period.getStartTimestamp());
            schedule.setEndTimestamp(period.getEndTimestamp());
            schedule.setPrice(period.getPriceInCentPerKWh());

            optimizedSchedules.add(schedule);
        }

        return optimizedSchedules;
    }

    /**
     * Saves the selected charging periods to the database, avoiding duplicate, expired, and overpriced entries.
     *
     * @param schedules Selected charging periods.
     */
    private void saveChargingSchedule(Set<ChargingSchedule> schedules) {
        long currentTime = System.currentTimeMillis();

        for (ChargingSchedule schedule : schedules) {
            // Skip invalid schedules directly
            if (!isValidSchedule(schedule, currentTime)) {
                continue;
            }

            // Check for duplicates using the repository method
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

            // Save valid schedule
            try {
                chargingScheduleRepository.save(schedule);
                LogFilter.log(
                        LogFilter.LOG_LEVEL_INFO,
                        String.format(
                                "Saved schedule: %s - %s at %.2f cents/kWh.",
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
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "All valid schedules saved successfully.");
    }

    /**
     * Validates whether a schedule meets basic criteria (e.g., not expired, not overpriced).
     *
     * @param schedule The schedule to validate.
     * @param currentTime The current system time.
     * @return True if the schedule is valid, false otherwise.
     */
    private boolean isValidSchedule(ChargingSchedule schedule, long currentTime) {
        // Check if the schedule is expired
        if (schedule.getEndTimestamp() < currentTime) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                    String.format("Skipping expired schedule: %s - %s (%.2f cents/kWh).",
                            dateFormat.format(new Date(schedule.getStartTimestamp())),
                            dateFormat.format(new Date(schedule.getEndTimestamp())),
                            schedule.getPrice()));
            return false;
        }

        // Check if the schedule is overpriced
        if (schedule.getPrice() > maxAcceptableMarketPriceInCent) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                    String.format("Skipping overpriced schedule: %s - %s at %.2f cents/kWh (max acceptable: %d cents/kWh).",
                            dateFormat.format(new Date(schedule.getStartTimestamp())),
                            dateFormat.format(new Date(schedule.getEndTimestamp())),
                            schedule.getPrice(),
                            maxAcceptableMarketPriceInCent));
            return false;
        }

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
                .collect(Collectors.toList());
    }
}
