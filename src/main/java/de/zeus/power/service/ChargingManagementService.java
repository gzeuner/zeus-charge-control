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

    @Value("${daytime.weighting.bonus:0.3}")
    private double daytimeWeightingBonus;

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
    public void optimizeChargingSchedule() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Starting optimization of charging schedule...");

        int currentRSOC = batteryManagementService.getRelativeStateOfCharge();

        // Überspringe Optimierung, wenn Ziel erreicht
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

        // Optimierung der Daytime-Perioden
        optimizeDaytimeCharging();

        // Optimierung der Nighttime-Perioden
        optimizeNighttimeCharging();

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimization of charging schedule completed.");
    }

    private void optimizeDaytimeCharging() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing daytime charging with priority for solar-friendly periods.");

        long currentTime = System.currentTimeMillis();
        Calendar dayStart = Calendar.getInstance();
        dayStart.set(Calendar.HOUR_OF_DAY, preferredStartHour); // Beginn der bevorzugten Tageszeit
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);

        Calendar dayEnd = (Calendar) dayStart.clone();
        dayEnd.set(Calendar.HOUR_OF_DAY, preferredEndHour); // Ende der bevorzugten Tageszeit

        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format(
                        "Daytime window: Start=%s, End=%s",
                        dateFormat.format(dayStart.getTime()),
                        dateFormat.format(dayEnd.getTime())
                )
        );

        // Wähle die günstigsten Perioden innerhalb der Tageszeit
        List<MarketPrice> cheapestDaytimePeriods = marketPriceRepository.findAll().stream()
                .filter(price -> price.getStartTimestamp() >= dayStart.getTimeInMillis())
                .filter(price -> price.getStartTimestamp() < dayEnd.getTimeInMillis())
                .filter(price -> price.getStartTimestamp() > currentTime)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(2) // Wähle die zwei günstigsten Tagesperioden
                .collect(Collectors.toList());

        if (cheapestDaytimePeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No daytime periods available for optimization.");
            return;
        }

        saveChargingSchedule(cheapestDaytimePeriods);

        for (MarketPrice period : cheapestDaytimePeriods) {
            Date startTime = new Date(period.getStartTimestamp());
            Date endTime = new Date(period.getEndTimestamp());

            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format(
                            "Scheduling charging task for daytime period: %s - %s at %.2f cents/kWh.",
                            dateFormat.format(startTime),
                            dateFormat.format(endTime),
                            period.getPriceInCentPerKWh()
                    )
            );

            taskScheduler.schedule(() -> executeChargingTask(startTime, endTime), startTime);
        }

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Daytime charging optimization completed.");
    }

    private void optimizeNighttimeCharging() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing nighttime charging with priority for cheapest periods.");

        long currentTime = System.currentTimeMillis();
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

        List<MarketPrice> cheapestNighttimePeriods = marketPriceRepository.findAll().stream()
                .filter(price -> price.getStartTimestamp() >= nightStart.getTimeInMillis())
                .filter(price -> price.getStartTimestamp() < nightEnd.getTimeInMillis())
                .filter(price -> price.getStartTimestamp() > currentTime)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(2) // Wähle die zwei günstigsten Perioden
                .collect(Collectors.toList());

        if (cheapestNighttimePeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No nighttime periods available for optimization.");
            return;
        }

        saveChargingSchedule(cheapestNighttimePeriods);

        for (MarketPrice period : cheapestNighttimePeriods) {
            Date startTime = new Date(period.getStartTimestamp());
            Date endTime = new Date(period.getEndTimestamp());

            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format(
                            "Scheduling charging task for nighttime period: %s - %s at %.2f cents/kWh.",
                            dateFormat.format(startTime),
                            dateFormat.format(endTime),
                            period.getPriceInCentPerKWh()
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


    /**
     * Selects the two cheapest periods from a given list of market prices.
     *
     * @param periods List of market prices to select from.
     * @param limit   Number of cheapest periods to select.
     * @return A list of the cheapest periods.
     */
    private List<MarketPrice> selectCheapestPeriods(List<MarketPrice> periods, int limit) {
        return periods.stream()
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Calculates the average price of a list of market prices.
     *
     * @param periods List of market prices.
     * @return The average price.
     */
    private double calculateAveragePrice(List<MarketPrice> periods) {
        return periods.stream()
                .mapToDouble(MarketPrice::getPriceInCentPerKWh)
                .average()
                .orElse(Double.MAX_VALUE); // Default to max value if no periods
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
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format(
                            "Re-enabling automatic mode and resetting charging point at %s",
                            dateFormat.format(finalResetTime)
                    )
            );
            batteryManagementService.resetToAutomaticMode();
        }, finalResetTime);

        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format(
                        "Task to re-enable automatic mode and reset charge point scheduled for %s",
                        dateFormat.format(finalResetTime)
                )
        );
    }

    private void schedulePostChargingReset() {
        // Check whether a reset is already planned
        if (resetScheduled.get()) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    "Reset task is already scheduled. Skipping duplicate scheduling."
            );
            return;
        }

        // Get all scheduled entries and sort them by end time
        List<ChargingSchedule> schedules = chargingScheduleRepository.findAll().stream()
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());

        if (schedules.isEmpty()) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    "No scheduled charging tasks found. Resetting charging point immediately."
            );
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
                LogFilter.log(
                        LogFilter.LOG_LEVEL_INFO,
                        String.format(
                                "Block end detected. Scheduling reset after last block ends at %s",
                                dateFormat.format(new Date(blockEndTime + 1000))
                        )
                );

                // Start a new block
                blockEndTime = schedules.get(i).getEndTimestamp();
            } else {
                // Update the end time of the current block
                blockEndTime = Math.max(blockEndTime, schedules.get(i).getEndTimestamp());
            }
        }

        // Add the last block to the reset list
        resetTimes.add(blockEndTime);
        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format(
                        "Final block detected. Scheduling reset after last block ends at %s",
                        dateFormat.format(new Date(blockEndTime + 1000))
                )
        );

        // Schedule resets for all times contained in the list
        for (Long resetTime : resetTimes) {
            scheduleResetTask(new Date(resetTime + 1000)); // Schedule one second after the end time
        }
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


    private List<MarketPrice> selectOptimalPeriods(List<MarketPrice> periods) {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Selecting optimal periods purely based on price.");

        // Sortiere Perioden ausschließlich nach dem Preis
        return periods.stream()
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(2) // Wähle die zwei günstigsten Perioden
                .collect(Collectors.toList());
    }


    private int calculatePeriodRSOC(MarketPrice period) {
        double chargingPowerInWatt = batteryManagementService.getChargingPointInWatt();
        double periodDurationInHours = (period.getEndTimestamp() - period.getStartTimestamp()) / 3600_000.0;
        double periodEnergyWh = chargingPowerInWatt * periodDurationInHours;

        int periodRSOC = (int) Math.round((periodEnergyWh / totalBatteryCapacity) * 100);

        LogFilter.log(
                LogFilter.LOG_LEVEL_DEBUG,
                String.format(
                        "Calculated RSOC contribution for period %s - %s: %d%%.",
                        dateFormat.format(new Date(period.getStartTimestamp())),
                        dateFormat.format(new Date(period.getEndTimestamp())),
                        periodRSOC
                )
        );

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
     * Logs skipped tasks with a specific reason.
     *
     * @param reason    The reason for skipping the task.
     * @param startTime The start time of the task.
     * @param endTime   The end time of the task.
     */
    private void logSkippedTask(String reason, Date startTime, Date endTime) {
        LogFilter.log(
                LogFilter.LOG_LEVEL_WARN,
                String.format(
                        "Skipping task for period %s - %s: %s.",
                        dateFormat.format(startTime),
                        dateFormat.format(endTime),
                        reason
                )
        );
    }

    /**
     * Determines if charging should continue based on the current battery state and operating mode.
     */
    private boolean shouldContinueCharging() {
        if (batteryManagementService.isBatteryCharging()) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    "Battery is already charging using solar energy. Preventing additional grid charging."
            );
            return false;
        }

        if (batteryManagementService.getRelativeStateOfCharge() >= targetStateOfCharge) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    "Battery has already reached target state of charge. No additional charging required."
            );
            return false;
        }

        if (batteryManagementService.isManualOperatingMode()) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    "Manual mode: Continuing charging for period."
            );
        } else if (batteryManagementService.isAutomaticOperatingMode()) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    "Automatic mode: Using free solar energy for period."
            );
        } else {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_WARN,
                    "Unknown operating mode. Skipping further charging logic."
            );
            return false;
        }
        return true;
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
     * Saves the selected charging periods to the database, avoiding duplicate entries.
     *
     * @param periods Selected charging periods.
     */
    private void saveChargingSchedule(List<MarketPrice> periods) {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Saving planned charging schedules...");

        // Iteriere über die neuen Perioden
        for (MarketPrice period : periods) {
            long periodStart = period.getStartTimestamp();

            // Suche existierende Perioden, die innerhalb von 6 Stunden vor oder nach der neuen Periode liegen
            List<ChargingSchedule> overlappingSchedules = chargingScheduleRepository.findAll().stream()
                    .filter(schedule -> Math.abs(schedule.getStartTimestamp() - periodStart) <= 6 * 60 * 60 * 1000) // Innerhalb von 6 Stunden
                    .filter(schedule -> schedule.getPrice() > period.getPriceInCentPerKWh()) // Teurer als die neue Periode
                    .collect(Collectors.toList());

            // Lösche alle teureren, sich überschneidenden Perioden
            for (ChargingSchedule schedule : overlappingSchedules) {
                chargingScheduleRepository.delete(schedule);
                LogFilter.log(
                        LogFilter.LOG_LEVEL_INFO,
                        String.format(
                                "Removed more expensive overlapping schedule: %s - %s at %.2f cents/kWh.",
                                dateFormat.format(new Date(schedule.getStartTimestamp())),
                                dateFormat.format(new Date(schedule.getEndTimestamp())),
                                schedule.getPrice()
                        )
                );
            }

            // Speichere die neue Periode
            ChargingSchedule schedule = new ChargingSchedule();
            schedule.setStartTimestamp(period.getStartTimestamp());
            schedule.setEndTimestamp(period.getEndTimestamp());
            schedule.setPrice(period.getPriceInCentPerKWh());
            chargingScheduleRepository.save(schedule);

            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format(
                            "Saved charging schedule: %s - %s at %.2f cents/kWh.",
                            dateFormat.format(new Date(schedule.getStartTimestamp())),
                            dateFormat.format(new Date(schedule.getEndTimestamp())),
                            schedule.getPrice()
                    )
            );
        }

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Charging schedules saving completed.");
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
