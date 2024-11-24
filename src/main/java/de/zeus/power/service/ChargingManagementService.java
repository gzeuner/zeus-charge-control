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

    @Value("${battery.minimum.stateOfCharge:40}")
    private int minimumStateOfCharge;

    @Value("${battery.target.stateOfCharge:90}")
    private int targetStateOfCharge;

    @Value("${marketdata.acceptable.price.cents:15}")
    private int maxAcceptableMarketPriceInCent;

    @Value("${battery.reduced.charge.factor:0.5}")
    private double reducedChargeFactor;

    @Value("${weather.api.cloudcover.threshold:60}")
    private double cloudCoverThreshold;

    @Value("${night.start:22}")
    private int nightStartHour;

    @Value("${night.end:6}")
    private int nightEndHour;

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
        logger.info("Current targetStateOfCharge: {}", targetStateOfCharge); // Debugging log
        int currentRSOC = batteryManagementService.getRelativeStateOfCharge();

        if (currentRSOC >= targetStateOfCharge) {
            logger.info("Skipping optimization: Current RSOC ({}) already meets or exceeds target RSOC ({}).", currentRSOC, targetStateOfCharge);
            return;
        }


        if (currentRSOC < minimumStateOfCharge) {
            logger.warn("Current RSOC ({}) is below the minimum allowed level ({}). Scheduling emergency reduced power charging.",
                    currentRSOC, minimumStateOfCharge);
            scheduleEmergencyCharging();
            return;
        }

        cleanUpExpiredSchedules();
        planOptimizedCharging(currentRSOC);
        logger.info("Optimization of charging schedule completed.");
    }

    /**
     * Plans and schedules optimized charging for both day and night periods based on market prices.
     *
     * @param currentRSOC Current battery state of charge.
     */
    private void planOptimizedCharging(int currentRSOC) {
        logger.info("Planning optimized charging...");
        List<MarketPrice> marketPrices = marketPriceRepository.findAll();

        if (marketPrices.isEmpty()) {
            logger.warn("No market prices available for charging.");
            return;
        }

        long currentTime = System.currentTimeMillis();
        Optional<Double> cloudCover = openMeteoService.getCurrentCloudCover();

        // Filter valid charging periods based on time and price thresholds
        List<MarketPrice> validPeriods = marketPrices.stream()
                .filter(p -> p.getStartTimestamp() > currentTime) // Future periods only
                .filter(p -> p.getPriceInCentPerKWh() <= maxAcceptableMarketPriceInCent) // Price threshold
                .collect(Collectors.toList());

        if (validPeriods.isEmpty()) {
            logger.info("No suitable periods found for charging.");
            return;
        }

        // Ignore weather data during nighttime
        if (!isNightPeriod() && cloudCover.isPresent()) {
            double currentCloudCover = cloudCover.get();
            logger.info("Current cloud cover: {}%, threshold: {}%", currentCloudCover, cloudCoverThreshold);

            if (currentCloudCover >= cloudCoverThreshold) {
                logger.warn("Cloud cover exceeds or equals threshold ({}%). Planning reduced network charging.", cloudCoverThreshold);
            } else {
                logger.info("Optimal solar conditions detected. Planning with minimal network charging.");
            }
        } else {
            logger.warn("Ignoring weather data as it is nighttime or unavailable.");
        }

        // Calculate required charging time
        int requiredChargingTime = calculateRequiredChargingTime(currentRSOC, targetStateOfCharge);

        // Select optimal periods based on required charging time
        List<MarketPrice> selectedPeriods = selectOptimalPeriods(validPeriods, requiredChargingTime);

        // Direct targetStateOfCharge handling for scenario 2
        if (currentRSOC >= targetStateOfCharge && !batteryManagementService.isLargeConsumerActive()) {
            logger.info("TargetStateOfCharge ({}) reached without large consumer. Activating AutomaticMode.", targetStateOfCharge);

            // Activate automatic mode and reset charging point
            boolean resetSuccess = batteryManagementService.resetToDefaultChargePoint();
            if (resetSuccess) {
                logger.info("Charging Point successfully reset to default.");
            } else {
                logger.error("Failed to reset Charging Point to default.");
            }

            batteryManagementService.resetToAutomaticMode();
            return; // No further resets required, as the target is already achieved
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

        saveChargingSchedule(selectedPeriods);

        // Check for large consumer activity
        boolean largeConsumerActive = batteryManagementService.isLargeConsumerActive();

        if (largeConsumerActive && isNightPeriod()) {
            // Schedule reduced power charging
            scheduleChargingTasksWithReducedPower(selectedPeriods);
            // Schedule reset to automatic mode at the end of the night period
            scheduleAutomaticModeReset();
        } else {
            // Schedule normal charging
            scheduleChargingTasks(selectedPeriods);
            // Reset to default after all charging tasks
            schedulePostChargingReset();
        }
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
     * Schedules reduced power charging tasks.
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
                        dateFormat.format(startTime), dateFormat.format(endTime));
                continue;
            }

            taskScheduler.schedule(() -> {
                logger.info("Scheduled reduced power charging task started for period: {} - {}.",
                        dateFormat.format(startTime), dateFormat.format(endTime));

                batteryManagementService.setReducedChargePoint();
                batteryManagementService.initCharging(true);

                // Set ChargingPoint to 0 after the period ends
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

    private boolean isDuplicateEntry(ChargingSchedule newSchedule) {
        return chargingScheduleRepository.findAll().stream()
                .anyMatch(existingSchedule ->
                        existingSchedule.getStartTimestamp() == newSchedule.getStartTimestamp() &&
                                existingSchedule.getEndTimestamp() == newSchedule.getEndTimestamp() &&
                                Double.compare(existingSchedule.getPrice(), newSchedule.getPrice()) == 0);
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

            boolean resetSuccess = batteryManagementService.resetToDefaultChargePoint();
            if (resetSuccess) {
                logger.info("Charging point reset to default successfully.");
            } else {
                logger.error("Failed to reset charging point to default.");
            }

            batteryManagementService.resetToAutomaticMode();

        }, finalResetTime);

        logger.info("Task to re-enable automatic mode and reset charge point scheduled for {}", dateFormat.format(finalResetTime));
    }

    private void schedulePostChargingReset() {
        // Überprüfen, ob bereits ein Reset geplant ist
        if (resetScheduled.get()) {
            logger.info("Reset task is already scheduled. Skipping duplicate scheduling.");
            return;
        }

        // Hole alle geplanten Einträge und sortiere sie nach Endzeitpunkt
        List<ChargingSchedule> schedules = chargingScheduleRepository.findAll().stream()
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());

        if (schedules.isEmpty()) {
            logger.info("No scheduled charging tasks found. Resetting charging point immediately.");
            batteryManagementService.resetToDefaultChargePoint();
            batteryManagementService.resetToAutomaticMode();
            return;
        }

        // Liste für die Endzeitpunkte, die einen Reset benötigen
        List<Long> resetTimes = new ArrayList<>();

        // Verarbeite die geplanten Perioden
        long blockEndTime = schedules.get(0).getEndTimestamp(); // Initialer Endzeitpunkt des ersten Blocks

        for (int i = 1; i < schedules.size(); i++) {
            long gap = schedules.get(i).getStartTimestamp() - blockEndTime;

            if (gap > 15 * 60 * 1000) { // Lücke größer als 15 Minuten erkannt
                // Füge den Endzeitpunkt des aktuellen Blocks zur Reset-Liste hinzu
                resetTimes.add(blockEndTime);
                logger.info("Block end detected. Scheduling reset after last block ends at {}", dateFormat.format(new Date(blockEndTime + 1000)));

                // Start eines neuen Blocks
                blockEndTime = schedules.get(i).getEndTimestamp();
            } else {
                // Aktualisiere den Endzeitpunkt des aktuellen Blocks
                blockEndTime = Math.max(blockEndTime, schedules.get(i).getEndTimestamp());
            }
        }

        // Füge den letzten Block zur Reset-Liste hinzu
        resetTimes.add(blockEndTime);
        logger.info("Final block detected. Scheduling reset after last block ends at {}", dateFormat.format(new Date(blockEndTime + 1000)));

        // Plane Resets für alle in der Liste enthaltenen Zeitpunkte
        for (Long resetTime : resetTimes) {
            scheduleResetTask(new Date(resetTime + 1000)); // Plane eine Sekunde nach dem Endzeitpunkt
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

            boolean resetSuccess = batteryManagementService.resetToDefaultChargePoint();
            if (resetSuccess) {
                logger.info("Charging point reset to default successfully.");
            } else {
                logger.error("Failed to reset charging point to default.");
            }

            batteryManagementService.resetToAutomaticMode();

            resetScheduled.set(false);

        }, resetTime);

        resetScheduled.set(true);
        logger.info("Task to reset charging point and re-enable automatic mode scheduled for {}", dateFormat.format(resetTime));
    }


    /**
     * Selects optimal periods for charging based on required charging time.
     *
     * @param periods              Available market periods.
     * @param requiredChargingTime Total required charging time in minutes.
     * @return A list of optimal periods for charging.
     */
    private List<MarketPrice> selectOptimalPeriods(List<MarketPrice> periods, int requiredChargingTime) {
        List<MarketPrice> selectedPeriods = new ArrayList<>();
        int accumulatedTime = 0;

        logger.info("Starting selection of optimal periods. Required charging time: {} minutes.", requiredChargingTime);

        // Sort periods by price (ascending), then by start time
        periods = periods.stream()
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh)
                        .thenComparingLong(MarketPrice::getStartTimestamp))
                .collect(Collectors.toList());

        for (MarketPrice period : periods) {
            // Stop if we've accumulated enough time
            if (accumulatedTime >= requiredChargingTime) {
                logger.info("Accumulated time {} minutes meets or exceeds required charging time. Stopping selection.", accumulatedTime);
                break;
            }

            // Add period to the selection
            selectedPeriods.add(period);
            accumulatedTime += 60; // Each period is assumed to be 60 minutes

            logger.info("Added period {} - {} (Price: {} cents/kWh). Accumulated time: {} minutes.",
                    dateFormat.format(new Date(period.getStartTimestamp())),
                    dateFormat.format(new Date(period.getEndTimestamp())),
                    period.getPriceInCentPerKWh(),
                    accumulatedTime);
        }

        if (selectedPeriods.isEmpty()) {
            logger.warn("No periods selected. Ensure that available periods and constraints are properly configured.");
        } else {
            logger.info("Total selected periods: {}. Final accumulated time: {} minutes.",
                    selectedPeriods.size(), accumulatedTime);
        }

        return selectedPeriods;
    }


    /**
     * Schedules emergency charging for the next immediate period.
     */
    private void scheduleEmergencyCharging() {
        List<MarketPrice> emergencyPeriods = marketPriceRepository.findAll().stream()
                .filter(p -> p.getStartTimestamp() > System.currentTimeMillis()) // Future periods only
                .sorted(Comparator.comparingLong(MarketPrice::getStartTimestamp))
                .limit(1) // Select the next immediate period
                .collect(Collectors.toList());

        if (emergencyPeriods.isEmpty()) {
            logger.error("No valid periods available for emergency charging.");
            return;
        }

        saveChargingSchedule(emergencyPeriods);

        for (MarketPrice period : emergencyPeriods) {
            Date startTime = new Date(period.getStartTimestamp());
            Date endTime = new Date(period.getEndTimestamp());

            taskScheduler.schedule(() -> {
                logger.info("Emergency charging task started for period: {} - {}.",
                        dateFormat.format(startTime), dateFormat.format(endTime));

                batteryManagementService.initCharging(true);

                // Task to execute at the end of the emergency period
                taskScheduler.schedule(() -> {
                    logger.info("Emergency charging task ended.");

                    boolean largeConsumerActive = batteryManagementService.isLargeConsumerActive();

                    if (!largeConsumerActive) {
                        logger.info("No large consumer active. Switching to AutomaticMode.");
                        boolean resetSuccess = batteryManagementService.resetToDefaultChargePoint();
                        if (resetSuccess) {
                            logger.info("ChargingPoint successfully reset to default after emergency charging.");
                        } else {
                            logger.error("Failed to reset ChargingPoint to default after emergency charging.");
                        }

                        batteryManagementService.resetToAutomaticMode();
                    } else {
                        logger.info("Large consumer active. Setting ChargingPoint to 0.");
                        boolean resetSuccess = batteryManagementService.setDynamicChargingPoint(0);
                        if (resetSuccess) {
                            logger.info("ChargingPoint successfully set to 0 after emergency charging.");
                        } else {
                            logger.error("Failed to set ChargingPoint to 0 after emergency charging.");
                        }

                        // Schedule the switch to AutomaticMode at nightEndHour
                        scheduleAutomaticModeReset();
                    }
                }, endTime);

            }, startTime);
        }

        logger.info("Emergency charging tasks successfully scheduled.");
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

            if (period.getStartTimestamp() <= currentTime) {
                logger.warn("Skipping task for period {} - {}: Start time is in the past.",
                        dateFormat.format(startTime), dateFormat.format(endTime));
                continue;
            }

            // Check if this task has already been scheduled
            if (isDuplicateTask(period)) {
                logger.info("Task already scheduled for period: {} - {}. Skipping.",
                        dateFormat.format(startTime), dateFormat.format(endTime));
                continue;
            }

            // Schedule the task
            taskScheduler.schedule(() -> {
                logger.info("Scheduled charging task started for period: {} - {}.",
                        dateFormat.format(startTime), dateFormat.format(endTime));

                batteryManagementService.initCharging(false);
            }, startTime);

            logger.info("Task scheduled for period: {} - {}.",
                    dateFormat.format(startTime), dateFormat.format(endTime));
        }

        logger.info("All charging tasks successfully scheduled.");
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
        periods.forEach(period -> {
            ChargingSchedule schedule = new ChargingSchedule();
            schedule.setStartTimestamp(period.getStartTimestamp());
            schedule.setEndTimestamp(period.getEndTimestamp());
            schedule.setPrice(period.getPriceInCentPerKWh());

            // Skip duplicate entries
            if (isDuplicateEntry(schedule)) {
                logger.info("Duplicate schedule detected. Skipping: {} - {} at {} cents/kWh.",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp())),
                        schedule.getPrice());
                return;
            }

            // Save and log non-duplicate schedule
            chargingScheduleRepository.save(schedule);
            logger.info("Saved charging schedule: {} - {} at {} cents/kWh.",
                    dateFormat.format(new Date(schedule.getStartTimestamp())),
                    dateFormat.format(new Date(schedule.getEndTimestamp())),
                    schedule.getPrice());
        });
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
