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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    @Value("${battery.capacity:10000}")
    private int totalBatteryCapacity;

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

    private final AtomicBoolean resetScheduled = new AtomicBoolean(false);
    private final AtomicBoolean nightResetScheduled = new AtomicBoolean(false);

    /**
     * Handles the MarketPricesUpdatedEvent and triggers the charging optimization process.
     *
     * @param event MarketPricesUpdatedEvent triggered when market prices are updated.
     */
    @EventListener
    public void onMarketPricesUpdated(MarketPricesUpdatedEvent event) {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Market prices updated event received. Recalculating charging schedule...");
        optimizeChargingSchedule();
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

    @Scheduled(fixedRateString = "${battery.automatic.mode.check.interval:300000}") // // Every 5 minutes
    public void checkAndResetToAutomaticMode() {
        if (batteryManagementService.isBatteryNotConfigured()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Battery not configured. Skipping automatic mode check.");
            return;
        }

        int currentRSOC = batteryManagementService.getRelativeStateOfCharge();

        // Check if RSOC has reached or exceeded the target
        if (currentRSOC >= targetStateOfCharge) {


            //Handle NightTime with large consumer
            if (isWithinNighttimeWindow(System.currentTimeMillis())) {
                if (batteryManagementService.isLargeConsumerActive()) {
                    LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Night period and large consumer detected. Setting charging point to 0.");
                    batteryManagementService.setDynamicChargingPoint(0);
                    scheduleEndOfNightReset();
                    return;
                }
            }

            // Return to Automatic Mode
            if (!isWithinNighttimeWindow(System.currentTimeMillis())) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Not nighttime and Large Consumer detected. Returning to Automatic Mode.");
                batteryManagementService.resetToAutomaticMode();
            }
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

    /**
     * Main method to optimize the charging schedule based on battery state, market prices, and time periods.
     */
    private void optimizeChargingSchedule() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Starting optimization of charging schedule...");

        int currentRSOC = batteryManagementService.getRelativeStateOfCharge();

        // Skip optimization if the target state of charge has been reached
        if (currentRSOC >= targetStateOfCharge) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format(
                            "Skipping optimization: Current RSOC (%d) already meets or exceeds target RSOC (%d).",
                            currentRSOC, targetStateOfCharge
                    )
            );
            return;
        }

        // Optimize nighttime charging
        optimizeNighttimeCharging();

        // Optimize transition periods between night and daytime
        optimizeTransitionPeriods();

        // Optimize daytime charging
        optimizeDaytimeCharging();

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimization of charging schedule completed.");
    }

    private List<MarketPrice> selectTopPeriods(List<MarketPrice> periods, int maxPeriods) {
        return periods.stream()
                .filter(price -> price.getPriceInCentPerKWh() <= maxAcceptableMarketPriceInCent)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(maxPeriods)
                .collect(Collectors.toList());
    }


    private void optimizeDaytimeCharging() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing daytime charging with priority for solar-friendly periods.");

        long currentTime = System.currentTimeMillis();

        // Set up the start and end time for the preferred daytime period
        Calendar dayStart = Calendar.getInstance();
        dayStart.set(Calendar.HOUR_OF_DAY, preferredStartHour);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);

        Calendar dayEnd = (Calendar) dayStart.clone();
        dayEnd.set(Calendar.HOUR_OF_DAY, preferredEndHour);

        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format(
                        "Daytime window: Start=%s, End=%s",
                        dateFormat.format(dayStart.getTime()),
                        dateFormat.format(dayEnd.getTime())
                )
        );

        // Collect market prices for the daytime period
        List<MarketPrice> daytimePeriods = selectTopPeriods(
                marketPriceRepository.findAll().stream()
                        .filter(price -> price.getStartTimestamp() >= dayStart.getTimeInMillis())
                        .filter(price -> price.getStartTimestamp() < dayEnd.getTimeInMillis())
                        .filter(price -> price.getStartTimestamp() > currentTime)
                        .collect(Collectors.toList()),
                maxChargingPeriods
        );

        if (daytimePeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No daytime periods available for optimization.");
            return;
        }

        // Optimize the collected schedules using a Set
        Set<ChargingSchedule> optimizedSchedules = collectAndOptimizeSchedules(daytimePeriods);

        // Save the optimized schedules
        saveChargingSchedule(optimizedSchedules);

        // Schedule the charging tasks
        for (ChargingSchedule schedule : optimizedSchedules) {
            Date startTime = new Date(schedule.getStartTimestamp());
            Date endTime = new Date(schedule.getEndTimestamp());

            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format(
                            "Scheduling charging task for daytime period: %s - %s at %.2f cents/kWh.",
                            dateFormat.format(startTime),
                            dateFormat.format(endTime),
                            schedule.getPrice()
                    )
            );

            taskScheduler.schedule(() -> executeChargingTask(startTime, endTime), startTime);
        }

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Daytime charging optimization completed.");
    }

    private void optimizeNighttimeCharging() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing nighttime charging with priority for cheapest periods.");

        long currentTime = System.currentTimeMillis();

        // Set up the start and end time for the nighttime period
        Calendar nightStart = Calendar.getInstance();
        nightStart.set(Calendar.HOUR_OF_DAY, nightStartHour);
        nightStart.set(Calendar.MINUTE, 0);
        nightStart.set(Calendar.SECOND, 0);
        nightStart.set(Calendar.MILLISECOND, 0);

        Calendar nightEnd = (Calendar) nightStart.clone();
        nightEnd.add(Calendar.DATE, 1);
        nightEnd.set(Calendar.HOUR_OF_DAY, nightEndHour);

        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format(
                        "Nighttime window: Start=%s, End=%s",
                        dateFormat.format(new Date(nightStart.getTimeInMillis())),
                        dateFormat.format(new Date(nightEnd.getTimeInMillis()))
                )
        );

        // Collect market prices for the nighttime period
        List<MarketPrice> nighttimePeriods = selectTopPeriods(
                marketPriceRepository.findAll().stream()
                        .filter(price -> price.getStartTimestamp() >= nightStart.getTimeInMillis())
                        .filter(price -> price.getStartTimestamp() < nightEnd.getTimeInMillis())
                        .filter(price -> price.getStartTimestamp() > currentTime)
                        .collect(Collectors.toList()),
                maxChargingPeriods
        );

        if (nighttimePeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No nighttime periods available for optimization.");
            return;
        }

        // Optimize the collected schedules using a Set
        Set<ChargingSchedule> optimizedSchedules = collectAndOptimizeSchedules(nighttimePeriods);

        // Save the optimized schedules
        saveChargingSchedule(optimizedSchedules);

        // Schedule the charging tasks
        for (ChargingSchedule schedule : optimizedSchedules) {
            Date startTime = new Date(schedule.getStartTimestamp());
            Date endTime = new Date(schedule.getEndTimestamp());

            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format(
                            "Scheduling charging task for nighttime period: %s - %s at %.2f cents/kWh.",
                            dateFormat.format(startTime),
                            dateFormat.format(endTime),
                            schedule.getPrice()
                    )
            );

            taskScheduler.schedule(() -> executeChargingTask(startTime, endTime), startTime);
        }

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Nighttime charging optimization completed.");
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


    private void scheduleResetTask(Date resetTime) {
        if (resetScheduled.get()) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    "Reset task is already scheduled. Skipping duplicate scheduling."
            );
            return;
        }

        taskScheduler.schedule(() -> {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format("Re-enabling automatic mode and resetting charging point at %s", dateFormat.format(resetTime))
            );
            batteryManagementService.resetToAutomaticMode();
            resetScheduled.set(false);
        }, resetTime);

        resetScheduled.set(true);
        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format("Task to reset charging point and re-enable automatic mode scheduled for %s", dateFormat.format(resetTime))
        );
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

    private void optimizeTransitionPeriods() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing transition periods between night and day.");

        Calendar nightEnd = Calendar.getInstance();
        nightEnd.set(Calendar.HOUR_OF_DAY, nightEndHour); // End of the night (e.g., 6:00 AM)
        nightEnd.set(Calendar.MINUTE, 0);
        nightEnd.set(Calendar.SECOND, 0);
        nightEnd.set(Calendar.MILLISECOND, 0);

        Calendar dayStart = Calendar.getInstance();
        dayStart.set(Calendar.HOUR_OF_DAY, preferredStartHour); // Start of the day (e.g., 10:00 AM)
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);

        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format(
                        "Transition period: Night end=%s, Day start=%s",
                        dateFormat.format(nightEnd.getTime()),
                        dateFormat.format(dayStart.getTime())
                )
        );

        // Find periods between night end and day start
        List<MarketPrice> transitionPeriods = selectTopPeriods(
                marketPriceRepository.findAll().stream()
                        .filter(price -> price.getStartTimestamp() >= nightEnd.getTimeInMillis())
                        .filter(price -> price.getEndTimestamp() <= dayStart.getTimeInMillis())
                        .collect(Collectors.toList()),
                maxChargingPeriods
        );


        if (transitionPeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No transition periods available for optimization.");
            return;
        }

        // Collect and optimize all periods
        Set<ChargingSchedule> uniqueSchedules = collectAndOptimizeSchedules(transitionPeriods);

        // Save optimized schedules
        saveChargingSchedule(uniqueSchedules);
    }

    private Set<ChargingSchedule> collectAndOptimizeSchedules(List<MarketPrice> periods) {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Collecting and optimizing schedules...");

        Set<ChargingSchedule> optimizedSchedules = new HashSet<>();

        for (MarketPrice period : periods) {
            ChargingSchedule schedule = new ChargingSchedule();
            schedule.setStartTimestamp(period.getStartTimestamp());
            schedule.setEndTimestamp(period.getEndTimestamp());
            schedule.setPrice(period.getPriceInCentPerKWh());

            // Remove existing schedules that are duplicates or more expensive
            optimizedSchedules.removeIf(existing ->
                    existing.getStartTimestamp() == schedule.getStartTimestamp() &&
                            existing.getEndTimestamp() == schedule.getEndTimestamp() &&
                            existing.getPrice() > schedule.getPrice()
            );

            // Add only new or cheaper schedules
            optimizedSchedules.add(schedule);
        }

        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format("Collected %d unique and optimized schedules.", optimizedSchedules.size())
        );

        return optimizedSchedules;
    }

    /**
     * Saves the selected charging periods to the database, avoiding duplicate entries.
     *
     * @param schedules Selected charging periods.
     */
    private void saveChargingSchedule(Set<ChargingSchedule> schedules) {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Saving optimized charging schedules...");

        for (ChargingSchedule schedule : schedules) {
            boolean isDuplicate = chargingScheduleRepository.existsByStartEndAndPrice(
                    schedule.getStartTimestamp(), schedule.getEndTimestamp(), schedule.getPrice()
            );

            if (isDuplicate) {
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

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "All schedules saved successfully.");
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
