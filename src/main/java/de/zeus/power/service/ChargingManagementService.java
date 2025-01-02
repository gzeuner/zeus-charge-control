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

        //Planung vorberechnen
        bufferDaytimeCharging();
        // Optimierung ausführen
        optimizeChargingSchedule();

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

    private void optimizeChargingSchedule() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing charging schedule...");

        Set<ChargingSchedule> optimizedSchedules = new HashSet<>();

        // Add nighttime charging optimization
        optimizedSchedules.addAll(optimizeNighttimeCharging());

        // Add daytime charging optimization
        optimizedSchedules.addAll(optimizeDaytimeCharging());

        // Dynamic threshold as a trigger
        int dynamicThreshold = calculateDynamicDaytimeThreshold();
        int currentRSOC = batteryManagementService.getRelativeStateOfCharge();

        if (currentRSOC <= dynamicThreshold) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "RSOC is below dynamic threshold (current: %d%%, threshold: %d%%). Initiating charging to reach target RSOC (%d%%).",
                    currentRSOC, dynamicThreshold, targetStateOfCharge
            ));

            // The goal is the configured targetStateOfCharge
            if (currentRSOC < targetStateOfCharge) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Scheduling charging to reach target RSOC.");
                optimizedSchedules.addAll(daytimeBuffer); // Use the planned charges from the buffer
            }
        } else {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "RSOC is above dynamic threshold (current: %d%%, threshold: %d%%). Skipping additional daytime charging.",
                    currentRSOC, dynamicThreshold
            ));
        }

        // Synchronize optimized schedules
        synchronizeSchedules(optimizedSchedules);
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Charging schedule optimization completed.");
    }

    private void bufferDaytimeCharging() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Buffering daytime charging periods dynamically based on price analysis.");

        long currentTime = System.currentTimeMillis();
        Calendar dayStart = Calendar.getInstance();
        dayStart.set(Calendar.HOUR_OF_DAY, preferredStartHour);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);

        Calendar dayEnd = (Calendar) dayStart.clone();
        dayEnd.set(Calendar.HOUR_OF_DAY, preferredEndHour);

        // Filter relevante Tageszeit-Perioden
        List<MarketPrice> daytimePeriods = marketPriceRepository.findAll().stream()
                .filter(price -> isWithinTimeWindow(price.getStartTimestamp(), dayStart.getTimeInMillis(), dayEnd.getTimeInMillis()))
                .filter(price -> price.getStartTimestamp() > currentTime)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .toList();

        if (daytimePeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No daytime periods available for buffering.");
            daytimeBuffer.clear(); // Sicherstellen, dass der Puffer leer ist
            return;
        }

        // Dynamische Schwellenwerte berechnen
        double threshold = calculateDynamicThreshold(daytimePeriods, priceFlexibilityThreshold);
        double maxAcceptablePrice = calculateMaxAcceptablePrice(
                batteryManagementService.getRelativeStateOfCharge(),
                maxAcceptableMarketPriceInCent
        );

        // Dynamische maximale Ladeperioden berechnen
        int dynamicMaxPeriods = calculateDynamicMaxChargingPeriods(daytimePeriods.size(), maxAcceptablePrice - threshold);

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Dynamic daytime threshold: %.2f cents/kWh, Max acceptable price: %.2f cents/kWh, Max periods: %d",
                threshold, maxAcceptablePrice, dynamicMaxPeriods));

        // Auswahl der Perioden basierend auf dynamischen Schwellenwerten
        List<MarketPrice> selectedPeriods = daytimePeriods.stream()
                .filter(price -> price.getPriceInCentPerKWh() <= threshold)
                .filter(price -> price.getPriceInCentPerKWh() <= maxAcceptablePrice)
                .limit(dynamicMaxPeriods)
                .toList();

        // Ladepuffer aktualisieren
        daytimeBuffer.clear();
        daytimeBuffer.addAll(collectAndOptimizeSchedules(selectedPeriods));

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format("Buffered %d daytime schedules for potential use.", daytimeBuffer.size()));
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
        if (isNightTime) {
            if(largeConsumerActive) {
                scheduleEndOfNightReset();
                LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                        "Scheduling Return to Automatic Mode.");
            }
        // Reset to automatic mode if outside night mode and targetStateOfCharge is reached
        } else {
            if(currentRSOC >= targetStateOfCharge && batteryManagementService.isManualOperatingMode()) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                        "Returning to Automatic Mode.");
                batteryManagementService.resetToAutomaticMode();
            } else {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Battery is in automatic mode. ");
            }
        }
    }


    private void scheduleEndOfNightReset() {
        if (nightResetScheduled.get()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Night reset already scheduled. Skipping duplicate scheduling.");
            return;
        }

        Date resetTime = getResetTime();

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

                // Check if we are outside the night period and a large consumer is active
                if (!isWithinTimeWindow(System.currentTimeMillis(),
                        getNightStart().getTimeInMillis(), getNightEnd(getNightStart()).getTimeInMillis()) && largeConsumerActive) {
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

    private int calculateDynamicMaxChargingPeriods(int totalPeriods, double priceRange) {
        int baseMaxPeriods = 4; // Standard-Wert

        if (priceRange < 0.5) {
            baseMaxPeriods += 2; // Mehr Perioden bei geringer Preisspanne
        } else if (priceRange > 1.0) {
            baseMaxPeriods -= 1; // Weniger Perioden bei großer Preisspanne
        }

        if (totalPeriods <= 5) {
            baseMaxPeriods = Math.min(baseMaxPeriods + 2, totalPeriods); // Flexibler bei wenigen Perioden
        } else if (totalPeriods > 10) {
            baseMaxPeriods = Math.max(baseMaxPeriods - 1, 2); // Begrenzung bei vielen Perioden
        }

        return Math.max(2, baseMaxPeriods); // Minimum von 2 Perioden
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

        // Filter relevante Tageszeit-Perioden
        List<MarketPrice> daytimePeriods = marketPriceRepository.findAll().stream()
                .filter(price -> isWithinTimeWindow(price.getStartTimestamp(), dayStart.getTimeInMillis(), dayEnd.getTimeInMillis()))
                .filter(price -> price.getStartTimestamp() > currentTime)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .toList();

        if (daytimePeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No daytime periods available for optimization.");
            return Collections.emptyList();
        }

        // Dynamische Schwellenwerte berechnen
        double threshold = calculateDynamicThreshold(daytimePeriods, priceFlexibilityThreshold);
        double maxAcceptablePrice = calculateMaxAcceptablePrice(
                batteryManagementService.getRelativeStateOfCharge(),
                maxAcceptableMarketPriceInCent
        );

        // Dynamische maximale Ladeperioden berechnen
        int dynamicMaxPeriods = calculateDynamicMaxChargingPeriods(daytimePeriods.size(), maxAcceptablePrice - threshold);

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Dynamic daytime threshold: %.2f cents/kWh, Max acceptable price: %.2f cents/kWh, Max periods: %d",
                threshold, maxAcceptablePrice, dynamicMaxPeriods));

        // Auswahl der Perioden basierend auf dynamischen Schwellenwerten
        List<MarketPrice> selectedPeriods = daytimePeriods.stream()
                .filter(price -> price.getPriceInCentPerKWh() <= threshold)
                .filter(price -> price.getPriceInCentPerKWh() <= maxAcceptablePrice)
                .limit(dynamicMaxPeriods)
                .toList();

        // Validierung der Perioden
        Set<ChargingSchedule> validatedSchedules = validateSchedulesForCheaperOptions(
                collectAndOptimizeSchedules(selectedPeriods)
        );

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Validated %d daytime charging schedules.", validatedSchedules.size()));

        // Konvertieren in Liste und zurückgeben
        return new ArrayList<>(validatedSchedules);
    }


    /**
     * Optimizes nighttime charging by dynamically selecting the best periods for the current night.
     * Allows prioritization of the cheapest three periods and optionally includes additional
     * periods based on dynamic thresholds.
     *
     * @return A list of optimized charging schedules.
     */
    private List<ChargingSchedule> optimizeNighttimeCharging() {
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing nighttime charging dynamically for the current night.");

        long currentTime = System.currentTimeMillis();

        // Define night start and end times, including date transition
        Calendar nightStart = Calendar.getInstance();
        nightStart.set(Calendar.HOUR_OF_DAY, nightStartHour);
        nightStart.set(Calendar.MINUTE, 0);
        nightStart.set(Calendar.SECOND, 0);
        nightStart.set(Calendar.MILLISECOND, 0);

        Calendar nightEnd = (Calendar) nightStart.clone();
        nightEnd.add(Calendar.DATE, 1); // Always set to the next day
        nightEnd.set(Calendar.HOUR_OF_DAY, nightEndHour);

        // Filter only relevant nighttime periods
        List<MarketPrice> nighttimePeriods = marketPriceRepository.findAll().stream()
                .filter(price -> price.getStartTimestamp() >= nightStart.getTimeInMillis() &&
                        price.getStartTimestamp() < nightEnd.getTimeInMillis())
                .filter(price -> price.getStartTimestamp() > currentTime)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .toList();

        if (nighttimePeriods.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No nighttime periods available for optimization.");
            return Collections.emptyList();
        }

        // Calculate dynamic thresholds and prioritize periods
        double threshold = calculateDynamicThreshold(nighttimePeriods, priceFlexibilityThreshold);
        double maxAcceptablePrice = calculateMaxAcceptablePrice(
                batteryManagementService.getRelativeStateOfCharge(),
                maxAcceptableMarketPriceInCent
        );

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Dynamic nighttime threshold: %.2f cents/kWh, Max acceptable price: %.2f cents/kWh",
                threshold, maxAcceptablePrice
        ));

        // Select top three cheapest periods and additional ones within tolerance
        int maxPeriods = 3; // Number of cheapest periods to prioritize
        int additionalPeriods = 2; // Allow 2 additional periods near the threshold

        List<MarketPrice> selectedPeriods = nighttimePeriods.stream()
                .filter(price -> price.getPriceInCentPerKWh() <= threshold ||
                        price.getPriceInCentPerKWh() <= nighttimePeriods.get(0).getPriceInCentPerKWh() * (1 + 0.02 * additionalPeriods))
                .filter(price -> price.getPriceInCentPerKWh() <= maxAcceptablePrice)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(maxPeriods + additionalPeriods)
                .toList();

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Selected %d periods for the current night, including %d additional periods close to the cheapest.",
                selectedPeriods.size(), additionalPeriods
        ));

        // Convert to ChargingSchedules and return
        return new ArrayList<>(collectAndOptimizeSchedules(selectedPeriods));
    }

    private double calculateDynamicMaxPrice(List<MarketPrice> nighttimePeriods) {
        double minPrice = nighttimePeriods.stream()
                .mapToDouble(MarketPrice::getPriceInCentPerKWh)
                .min()
                .orElse(maxAcceptableMarketPriceInCent);

        double priceRange = nighttimePeriods.stream()
                .mapToDouble(MarketPrice::getPriceInCentPerKWh)
                .max()
                .orElse(minPrice) - minPrice;

        double tolerance = priceRange > 5 ? 0.30 : 0.20;

        double dynamicMaxPrice = minPrice + (minPrice * tolerance);

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Calculated dynamic maximum price: %.2f cents/kWh (minPrice: %.2f, tolerance: %.2f)",
                dynamicMaxPrice, minPrice, tolerance
        ));

        return dynamicMaxPrice;
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

    private int calculateDynamicDaytimeThreshold() {
        List<Map.Entry<Long, Integer>> history = batteryManagementService.getRsocHistory();

        if (history.size() < 2) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Not enough RSOC history data to calculate dynamic daytime threshold.");
            return targetStateOfCharge - 20; // Default threshold
        }

        Map.Entry<Long, Integer> oldest = history.get(0);
        Map.Entry<Long, Integer> latest = history.get(history.size() - 1);

        long timeDifferenceInMinutes = (latest.getKey() - oldest.getKey()) / 60000;
        if (timeDifferenceInMinutes <= 0) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "Invalid time difference in RSOC history. Using default threshold.");
            return targetStateOfCharge - 20; // Fallback threshold
        }

        int rsocDifference = oldest.getValue() - latest.getValue();

        double rsocDropPerHour = rsocDifference / (timeDifferenceInMinutes / 60.0);
        LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                String.format("RSOC drop rate: %.2f%%/hour based on history.", rsocDropPerHour));

        // Adjust threshold dynamically based on RSOC drop rate
        int dynamicThreshold = (int) Math.max(targetStateOfCharge - (rsocDropPerHour * 2), targetStateOfCharge - 30);

        LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                String.format("Dynamic daytime threshold calculated: %d%%", dynamicThreshold));
        return dynamicThreshold;
    }


    private boolean isWithinNighttimeWindow(long timestamp) {

        Calendar nightStart = getNightStart();
        Calendar nightEnd = getNightEnd(nightStart);

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


        return isWithinTimeWindow(timestamp, nightStart.getTimeInMillis(), nightEnd.getTimeInMillis());
    }

    private Calendar getNightEnd(Calendar nightStart) {
        // End of the night (always the next day after the night start)
        Calendar nightEnd = (Calendar) nightStart.clone();
        nightEnd.add(Calendar.DATE, 1);
        nightEnd.set(Calendar.HOUR_OF_DAY, nightEndHour);
        return nightEnd;
    }

    /**
     * Validates charging schedules by applying price tolerance thresholds and ensuring
     * no gaps between charging periods.
     *
     * @param schedules The set of charging schedules to validate.
     * @return A set of validated and optimized charging schedules.
     */
    private Set<ChargingSchedule> validateSchedulesForCheaperOptions(Set<ChargingSchedule> schedules) {
        // Sort schedules by price (ascending order)
        List<ChargingSchedule> sortedSchedules = schedules.stream()
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice))
                .toList();

        Set<ChargingSchedule> validatedSchedules = new HashSet<>();

        // Calculate minimum and maximum prices in the provided schedules
        double minPrice = sortedSchedules.stream()
                .mapToDouble(ChargingSchedule::getPrice)
                .min()
                .orElse(Double.MAX_VALUE);
        double maxPrice = sortedSchedules.stream()
                .mapToDouble(ChargingSchedule::getPrice)
                .max()
                .orElse(Double.MIN_VALUE);

        // Dynamically calculate the price tolerance factor and threshold
        double priceToleranceFactor = calculateDynamicPriceTolerance(minPrice, maxPrice, sortedSchedules.size());

        // Define fallback margin and dynamically adjust threshold
        double fallbackMargin = 0.50; // Minimum margin of 0.50 cents/kWh
        double priceRangeFactor = (maxPrice - minPrice) / maxPrice; // Normalize range
        double dynamicTolerance = 0.05 + (sortedSchedules.size() * 0.01); // Increment by 1% per period
        double adjustedToleranceFactor = priceToleranceFactor + (0.05 * priceRangeFactor) + dynamicTolerance;
        double maxAcceptablePrice = this.maxAcceptableMarketPriceInCent; // Example: max 15 cents/kWh
        double priceThreshold = Math.min(
                Math.max(minPrice * adjustedToleranceFactor, minPrice + fallbackMargin),
                maxAcceptablePrice
        );

        // Allow additional periods within a tolerance range
        int additionalPeriods = 2; // Adjustable
        double extendedThreshold = priceThreshold * (1 + 0.02 * additionalPeriods);

        // Dynamically determine the maximum number of charging periods
        int maxChargingPeriods = calculateDynamicMaxChargingPeriods(sortedSchedules.size(), maxPrice - minPrice);

        // Validate each schedule based on the calculated threshold
        for (ChargingSchedule schedule : sortedSchedules) {
            boolean isWithinTolerance = schedule.getPrice() <= priceThreshold ||
                    (validatedSchedules.size() < additionalPeriods &&
                            schedule.getPrice() <= extendedThreshold);

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

        // Fallback: If no periods are validated, select the two cheapest periods
        if (validatedSchedules.isEmpty() && sortedSchedules.size() >= 2) {
            validatedSchedules.add(sortedSchedules.get(0));
            validatedSchedules.add(sortedSchedules.get(1));
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Fallback: Selected two cheapest periods due to empty validation result.");
        }

        return validatedSchedules;
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



    private double calculateDynamicPriceTolerance(double minPrice, double maxPrice, int periodCount) {
        double priceRange = maxPrice - minPrice;
        double baseTolerance = 1.5; // Standard-Toleranz

        // Anpassung basierend auf Preisspanne
        if (priceRange < 0.5) {
            baseTolerance -= 0.2; // Engere Toleranz bei kleiner Preisspanne
        } else if (priceRange > 1.0) {
            baseTolerance += 0.3; // Höhere Toleranz bei großer Preisspanne
        }

        // Anpassung basierend auf Periodenanzahl
        if (periodCount <= 5) {
            baseTolerance += 0.3; // Wenige Perioden → großzügiger
        } else if (periodCount > 10) {
            baseTolerance -= 0.2; // Viele Perioden → restriktiver
        }

        // Zusätzlicher Puffer basierend auf günstigsten Preisen
        if (minPrice < 0.1) {
            baseTolerance += 0.1; // Etwas großzügiger bei sehr niedrigen Preisen
        }

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
                .toList();

        // Validate new schedules (retain only cheaper alternatives)
        Set<ChargingSchedule> validatedNewSchedules = validateSchedulesForCheaperOptions(newSchedules);

        // Remove outdated schedules if they are replaced by cheaper alternatives
        for (ChargingSchedule existing : existingSchedules) {
            boolean hasCheaperAlternative = validatedNewSchedules.stream()
                    .anyMatch(newSchedule -> newSchedule.getStartTimestamp().equals(existing.getStartTimestamp())
                            && newSchedule.getEndTimestamp().equals(existing.getEndTimestamp())
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
                .toList();
    }

    private boolean isWithinTimeWindow(long timestamp, long start, long end) {
        return timestamp >= start && timestamp < end;
    }

}
