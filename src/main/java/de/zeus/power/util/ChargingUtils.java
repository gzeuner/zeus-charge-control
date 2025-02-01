package de.zeus.power.util;

import de.zeus.power.config.LogFilter;
import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.repository.ChargingScheduleRepository;
import de.zeus.power.service.BatteryManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.*;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class ChargingUtils {

    @Value("${BATTERY_MAX_CAPACITY:10000}")
    private int maxCapacityInWatt;

    @Value("${battery.target.stateOfCharge}")
    private int targetStateOfChargeInPercent;

    @Value("${battery.chargingPoint}")
    private int chargingPointInWatt;

    @Value("${marketdata.price.flexibility.threshold:10}")
    private double priceFlexibilityThreshold;

    @Value("${battery.target.stateOfCharge:90}")
    private int targetStateOfCharge;

    @Value("${marketdata.acceptable.price.cents:15}")
    private int maxAcceptableMarketPriceInCent;

    @Autowired
    private BatteryManagementService batteryManagementService;

    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * Validates whether a charging schedule meets basic criteria (e.g., not expired, not overpriced).
     * Also ensures it adheres to dynamic price thresholds calculated based on market prices.
     *
     * @param schedule           The schedule to validate.
     * @param currentTime        The current system time in milliseconds.
     * @param validatedSchedules A set of already validated schedules for overlap checks.
     * @param marketPrices       The list of market prices for dynamic threshold calculation.
     * @return True if the schedule is valid, false otherwise.
     */
    public boolean isValidSchedule(
            ChargingSchedule schedule,
            long currentTime,
            Set<ChargingSchedule> validatedSchedules,
            List<MarketPrice> marketPrices
    ) {
        // Check if the schedule is expired
        if (isScheduleExpired(schedule, currentTime)) {
            return false;
        }

        // Check if the schedule is overpriced
        if (isScheduleOverpriced(schedule)) {
            return false;
        }

        // Check for overlapping schedules
        if (isScheduleOverlapping(schedule, validatedSchedules)) {
            return false;
        }

        // Validate against dynamic price thresholds
        if (exceedsDynamicThreshold(schedule, marketPrices)) {
            return false;
        }

        // Schedule meets all validation criteria
        return true;
    }


    /**
     * Calculates the required energy to reach the target RSOC.
     *
     * @param currentRSOC The current relative state of charge (in percentage).
     * @return The required energy in watt-hours.
     */
    public double calculateRequiredCapacity(int currentRSOC) {
        double currentCapacity = (currentRSOC / 100.0) * maxCapacityInWatt;
        double targetCapacity = (targetStateOfChargeInPercent / 100.0) * maxCapacityInWatt;
        return targetCapacity - currentCapacity;
    }

    /**
     * Calculates the number of required periods based on energy needs and available periods.
     *
     * @param requiredCapacity The required energy in watt-hours.
     * @param totalPeriods     The total number of available periods.
     * @return The required number of periods.
     */
    public int calculateRequiredPeriods(double requiredCapacity, int totalPeriods) {
        double energyPerPeriod = chargingPointInWatt * 3600; // Assuming 1-hour periods
        int requiredPeriods = (int) Math.ceil(requiredCapacity / energyPerPeriod);
        return Math.min(requiredPeriods, totalPeriods);
    }

    /**
     * Filters and sorts future charging schedules.
     *
     * @param schedules   All charging schedules.
     * @param currentTime The current timestamp in milliseconds.
     * @return A sorted list of future schedules.
     */
    public List<ChargingSchedule> getFutureChargingSchedules(List<ChargingSchedule> schedules, long currentTime) {
        List<ChargingSchedule> futureSchedules = schedules.stream()
                .filter(schedule -> schedule.getEndTimestamp() > currentTime)
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());

        if (futureSchedules.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "No future schedules found. Ensure the calling code handles this scenario gracefully.");
        }

        return futureSchedules;
    }
    /**
     * Handles transitions to automatic mode based on the current time and RSOC state.
     *
     * @param currentTimeMillis The current time in milliseconds.
     */
    public void handleAutomaticModeTransition(long currentTimeMillis) {
        boolean isNight = isNight(currentTimeMillis);
        int currentRSOC = batteryManagementService.getRelativeStateOfCharge();

        // Log the current state for debugging
        LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                String.format("Current time: %d, isNight: %b, RSOC: %d%%, Target RSOC: %d%%",
                        currentTimeMillis, isNight, currentRSOC, targetStateOfCharge));

        if (isNight && currentRSOC >= targetStateOfCharge) {
            // During nighttime and RSOC has reached or exceeded the target, stop charging
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Nighttime and RSOC target reached. Setting dynamic charging point to 0.");
            batteryManagementService.setDynamicChargingPoint(0);
        } else if (!isNight && currentRSOC >= targetStateOfCharge) {
            // During daytime, ensure automatic mode is active
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Daytime and RSOC target reached. Returning to Automatic Mode.");
            batteryManagementService.resetToAutomaticMode();
        }
    }


    public static boolean isNight(long currentTimeMillis) {
        int nightStartHour = NightConfig.getNightStartHour();
        int nightEndHour = NightConfig.getNightEndHour();
        ZoneId zoneId = ZoneId.systemDefault();

        Instant currentInstant = Instant.ofEpochMilli(currentTimeMillis);
        LocalDateTime currentDateTime = LocalDateTime.ofInstant(currentInstant, zoneId);
        LocalDate currentDate = currentDateTime.toLocalDate();

        LocalDateTime nightStart;
        LocalDateTime nightEnd;

        // If the night period extends beyond midnight (e.g. 19 to 6)
        if (nightStartHour > nightEndHour) {
            // If the current time is before the end of the night, it belongs to the night of the previous day
            if (currentDateTime.getHour() < nightEndHour) {
                nightStart = LocalDateTime.of(currentDate.minusDays(1), LocalTime.of(nightStartHour, 0));
                nightEnd = LocalDateTime.of(currentDate, LocalTime.of(nightEndHour, 0));
            } else {
                // otherwise the night period is calculated from the current day to the next da
                nightStart = LocalDateTime.of(currentDate, LocalTime.of(nightStartHour, 0));
                nightEnd = LocalDateTime.of(currentDate.plusDays(1), LocalTime.of(nightEndHour, 0));
            }
        } else {
            // If the night period does not extend beyond midnight
            nightStart = LocalDateTime.of(currentDate, LocalTime.of(nightStartHour, 0));
            nightEnd = LocalDateTime.of(currentDate, LocalTime.of(nightEndHour, 0));
        }

        // Log output for checking
        LogFilter.log(LogFilter.LOG_LEVEL_DEBUG, String.format(
                "Night period: Start=%s, End=%s, Current=%s -> isNight=%b",
                nightStart, nightEnd, currentDateTime,
                !currentDateTime.isBefore(nightStart) && currentDateTime.isBefore(nightEnd)
        ));

        return !currentDateTime.isBefore(nightStart) && currentDateTime.isBefore(nightEnd);
    }


    /**
     * Creates and logs a new charging schedule based on a market price.
     *
     * @param period                  The market price period to schedule.
     * @param chargingScheduleRepository The repository to save the schedule.
     * @param dateFormat              The date format for logging.
     */
    public void createAndLogChargingSchedule(MarketPrice period, ChargingScheduleRepository chargingScheduleRepository, DateFormat dateFormat) {
        ChargingSchedule newSchedule = new ChargingSchedule();
        newSchedule.setStartTimestamp(period.getStartTimestamp());
        newSchedule.setEndTimestamp(period.getEndTimestamp());
        newSchedule.setPrice(period.getPriceInCentPerKWh());

        chargingScheduleRepository.save(newSchedule);

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Added new charging period: %s - %s (%.2f cents/kWh).",
                dateFormat.format(new Date(period.getStartTimestamp())),
                dateFormat.format(new Date(period.getEndTimestamp())),
                period.getPriceInCentPerKWh()));
    }

    /**
     * Calculates the price range for a list of market price periods.
     * The price range is the difference between the maximum and minimum prices.
     * If the list is empty, the range is 0.
     *
     * @param periods The list of market price periods.
     * @return The price range (max - min) in cents/kWh.
     */
    public double calculatePriceRange(List<MarketPrice> periods) {
        if (periods == null || periods.isEmpty()) {
            LogFilter.logWarn("Market price periods list is empty or null. Returning price range: 0.0.");
            return 0.0;
        }

        double minPrice = periods.stream()
                .mapToDouble(MarketPrice::getPriceInCentPerKWh)
                .min()
                .orElse(0.0);

        double maxPrice = periods.stream()
                .mapToDouble(MarketPrice::getPriceInCentPerKWh)
                .max()
                .orElse(0.0);

        double priceRange = maxPrice - minPrice;

        LogFilter.logInfo("Calculated price range: %.2f (min: %.2f, max: %.2f).", priceRange, minPrice, maxPrice);

        return priceRange;
    }

    /**
     * Converts a MarketPrice to a ChargingSchedule.
     */
    public ChargingSchedule convertToChargingSchedule(MarketPrice price) {
        ChargingSchedule schedule = new ChargingSchedule();
        schedule.setStartTimestamp(price.getStartTimestamp());
        schedule.setEndTimestamp(price.getEndTimestamp());
        schedule.setPrice(price.getPriceInCentPerKWh());
        return schedule;
    }

    public Optional<MarketPrice> findCheaperFuturePeriod(MarketPrice currentPeriod, List<MarketPrice> allPeriods) {
        // Look for a future period that starts after the current period and has a lower price
        return allPeriods.stream()
                .filter(price -> price.getStartTimestamp() > currentPeriod.getEndTimestamp())
                .filter(price -> price.getPriceInCentPerKWh() < currentPeriod.getPriceInCentPerKWh())
                .findFirst();
    }

    public List<MarketPrice> findBestNightPeriods(List<MarketPrice> nighttimePeriods, int maxPeriods) {
        List<MarketPrice> bestPeriods = new ArrayList<>();

        List<MarketPrice> sortedByPrice = nighttimePeriods.stream()
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .toList();

        double minPrice = sortedByPrice.get(0).getPriceInCentPerKWh();
        long lastEndTimestamp = -1;

        for (MarketPrice period : sortedByPrice) {
            if (bestPeriods.size() >= maxPeriods) break;

            boolean isAcceptablePrice = period.getPriceInCentPerKWh() <= minPrice + 0.5;
            boolean isConsecutive = lastEndTimestamp == -1 || period.getStartTimestamp() == lastEndTimestamp;

            if (isAcceptablePrice || isConsecutive) {
                bestPeriods.add(period);
                lastEndTimestamp = period.getEndTimestamp();
            }
        }
        return bestPeriods;
    }

    /**
     * Checks if the schedule price exceeds the calculated dynamic threshold.
     */
    public boolean exceedsDynamicThreshold(ChargingSchedule schedule, List<MarketPrice> marketPrices) {
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
            return true;
        }
        return false;
    }

    /**
     * Calculates a dynamic threshold for selecting charging periods based on market prices.
     * Considers the range and distribution of prices to adjust the threshold dynamically.
     *
     * @param prices          The list of market prices.
     * @param defaultThreshold The default flexibility threshold.
     * @return The calculated dynamic threshold.
     */
    public double calculateDynamicThreshold(List<MarketPrice> prices, double defaultThreshold) {
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

    /**
     * Calculates the maximum acceptable price for charging based on the current RSOC (Relative State of Charge).
     * If the RSOC is below 80% of the target state of charge, the base price is increased by 20%.
     *
     * @param currentRSOC The current relative state of charge (in percentage).
     * @param basePrice   The base acceptable price (in cents/kWh).
     * @return The maximum acceptable price for charging.
     */
    public double calculateMaxAcceptablePrice(double currentRSOC, double basePrice) {
        final double rsocThreshold = targetStateOfCharge * 0.8;
        final double priceMultiplier = 1.2;

        if (currentRSOC < rsocThreshold) {
            LogFilter.logInfo("RSOC (%.2f%%) is below threshold (%.2f%%). Applying price multiplier: %.2f.",
                    currentRSOC, rsocThreshold, priceMultiplier);
            return basePrice * priceMultiplier;
        }

        LogFilter.logInfo("RSOC (%.2f%%) is above or equal to threshold (%.2f%%). Using base price: %.2f.",
                currentRSOC, rsocThreshold, basePrice);
        return basePrice;
    }


    /**
     * Calculates a dynamic daytime threshold for charging based on the RSOC (Relative State of Charge) drop rate.
     * The threshold adjusts dynamically to prioritize charging when the RSOC is depleting faster than expected.
     *
     * Falls back to a default threshold if there is insufficient historical data or invalid time differences.
     *
     * @return The calculated dynamic daytime threshold as a percentage of the target state of charge.
     */
    public int calculateDynamicDaytimeThreshold() {
        // Retrieve the RSOC history from the battery management service
        List<Map.Entry<Long, Integer>> history = batteryManagementService.getRsocHistory();

        // Fallback threshold in case of insufficient or invalid data
        final int fallbackThreshold = targetStateOfCharge - 20;

        // Ensure there is enough historical data
        if (history.size() < 2) {
            LogFilter.logInfo("Insufficient RSOC history data. Using fallback threshold: %d%%", fallbackThreshold);
            return fallbackThreshold;
        }

        // Extract the oldest and latest RSOC history entries
        Map.Entry<Long, Integer> oldest = history.get(0);
        Map.Entry<Long, Integer> latest = history.get(history.size() - 1);

        // Calculate time difference in minutes
        long timeDifferenceInMinutes = (latest.getKey() - oldest.getKey()) / 60000;
        if (timeDifferenceInMinutes <= 0) {
            LogFilter.logWarn("Invalid time difference in RSOC history. Using fallback threshold: %d%%", fallbackThreshold);
            return fallbackThreshold;
        }

        // Calculate the RSOC drop rate (percentage per hour)
        int rsocDifference = oldest.getValue() - latest.getValue();
        double rsocDropPerHour = rsocDifference / (timeDifferenceInMinutes / 60.0);

        // Log the calculated drop rate
        LogFilter.logInfo("RSOC drop rate: %.2f%%/hour based on history.", rsocDropPerHour);

        // Calculate the dynamic threshold based on the drop rate
        int dynamicThreshold = (int) Math.max(targetStateOfCharge - (rsocDropPerHour * 2), targetStateOfCharge - 30);

        // Ensure the threshold doesn't exceed logical limits
        dynamicThreshold = Math.min(dynamicThreshold, targetStateOfCharge);

        // Log the calculated threshold
        LogFilter.logInfo("Dynamic daytime threshold calculated: %d%%", dynamicThreshold);

        return dynamicThreshold;
    }

    /**
     * Helper method to check if a cheaper future schedule exists and adjust the schedules accordingly.
     * Ensures critical RSOC (Relative State of Charge) levels and nighttime schedules are prioritized.
     *
     * @param currentSchedule The current charging schedule being evaluated.
     * @param schedulesToEvaluate The list of all available charging schedules to evaluate against.
     * @param chargingScheduleRepository The repository ti access schedule data
     * @return True if a new schedule was created for a cheaper future option, false otherwise.
     */
    public boolean adjustForCheaperFutureSchedule(ChargingSchedule currentSchedule,
                                                   List<ChargingSchedule> schedulesToEvaluate,
                                                   ChargingScheduleRepository chargingScheduleRepository) {
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

    /**
     * Converts a list of market price periods into a set of optimized charging schedules.
     * Each period is transformed into a corresponding charging schedule, preserving
     * the start time, end time, and price attributes.
     *
     * @param periods A list of MarketPrice objects representing the charging periods.
     * @return A set of ChargingSchedule objects optimized from the given periods.
     */
    public Set<ChargingSchedule> collectAndOptimizeSchedules(List<MarketPrice> periods) {
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
     * Removes excess charging periods from the existing schedules.
     *
     * @param existingSchedules The list of currently scheduled charging periods.
     * @param periodsToRemove   The number of periods to remove.
     */
    public void removeExcessChargingPeriods(List<ChargingSchedule> existingSchedules,
                                             int periodsToRemove,
                                             ChargingScheduleRepository chargingScheduleRepository,
                                             Map<Long, ScheduledFuture<?>> scheduledTasks) {
        for (int i = 0; i < periodsToRemove; i++) {
            ChargingSchedule scheduleToRemove = existingSchedules.get(existingSchedules.size() - 1 - i);
            cancelTask(scheduleToRemove.getId(), scheduledTasks);
            chargingScheduleRepository.delete(scheduleToRemove);

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Removed charging period: %s - %s.",
                    dateFormat.format(new Date(scheduleToRemove.getStartTimestamp())),
                    dateFormat.format(new Date(scheduleToRemove.getEndTimestamp()))));
        }
    }

    public void cancelTask(long scheduleId, Map<Long, ScheduledFuture<?>> scheduledTasks) {
        ScheduledFuture<?> task = scheduledTasks.remove(scheduleId);
        if (task != null) {
            task.cancel(true);
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Cancelled scheduled task for schedule ID: " + scheduleId);
        }
    }


    /**
     * Cleans up expired charging schedules and optimizes remaining schedules.
     */
    public void cleanUpExpiredSchedules(ChargingScheduleRepository chargingScheduleRepository,
                                         Map<Long, ScheduledFuture<?>> scheduledTasks) {
        long currentTime = System.currentTimeMillis();

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Starting cleanup of expired charging schedules...");

        // Load all schedules once
        List<ChargingSchedule> allSchedules = chargingScheduleRepository.findAll();

        // Step 1: Remove expired schedules
        List<ChargingSchedule> expiredSchedules = allSchedules.stream()
                .filter(schedule -> schedule.getEndTimestamp() < currentTime)
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
                if (adjustForCheaperFutureSchedule(currentSchedule, schedulesToEvaluate, chargingScheduleRepository)) {
                    cancelTask(currentSchedule.getId(), scheduledTasks);
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
                .filter(schedule -> schedule.getEndTimestamp() > currentTime)
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice))
                .toList();

        if (!futureSchedules.isEmpty()) {
            futureSchedules = optimizeRemainingSchedules(futureSchedules);

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
     * Checks if the schedule is expired based on the current time.
     */
    public boolean isScheduleExpired(ChargingSchedule schedule, long currentTime) {
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
            return true;
        }
        return false;
    }

    /**
     * Checks if the schedule price exceeds the maximum acceptable price.
     */
    public boolean isScheduleOverpriced(ChargingSchedule schedule) {
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
            return true;
        }
        return false;
    }

    /**
     * Checks if the schedule overlaps with already validated schedules.
     */
    public boolean isScheduleOverlapping(ChargingSchedule schedule, Set<ChargingSchedule> validatedSchedules) {
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
            return true;
        }
        return false;
    }

    /**
     * Calculates the dynamic maximum number of charging periods based on the required energy
     * to reach the target state of charge, electricity price range, and total available periods.
     *
     * @param totalPeriods  The total number of available charging periods.
     * @param priceRange    The range of electricity prices in the current schedule.
     * @param currentRSOC   The current relative state of charge of the battery (in percentage).
     * @return The calculated maximum number of charging periods to use.
     */
    public int calculateDynamicMaxChargingPeriods(int totalPeriods, double priceRange, int currentRSOC) {
        // Calculate the remaining capacity needed to reach the target RSOC
        double currentCapacity = (currentRSOC / 100.0) * maxCapacityInWatt;
        double targetCapacity = (targetStateOfChargeInPercent / 100.0) * maxCapacityInWatt;
        double requiredCapacity = targetCapacity - currentCapacity;

        if (requiredCapacity <= 0) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                    String.format("Battery already at or above target RSOC (%d%%). No additional charging required.",
                            targetStateOfChargeInPercent));
            return 0; // No charging periods needed if target RSOC is already reached
        }

        // Calculate the maximum possible energy input per period ( = 1 hour = 3600 seconds)
        double energyPerPeriod = chargingPointInWatt * 3600;

        // Determine the base number of periods needed to fulfill the energy requirement
        int basePeriods = (int) Math.ceil(requiredCapacity / energyPerPeriod);

        // Adjust based on price range
        if (priceRange < 0.5) {
            basePeriods += 2; // Allow more periods if price range is narrow
        } else if (priceRange > 2.0) {
            basePeriods -= 1; // Reduce periods if price range is wide
        }

        // Adjust dynamically based on the number of total periods available
        if (totalPeriods <= 5) {
            basePeriods = Math.min(basePeriods + 1, totalPeriods); // More flexibility with fewer periods
        } else if (totalPeriods > 10) {
            basePeriods = Math.max(basePeriods - 1, 2); // Stricter allocation with many periods
        }

        // Ensure a minimum of 2 periods and a maximum of the total available periods
        int dynamicMaxPeriods = Math.max(2, basePeriods);
        dynamicMaxPeriods = Math.min(dynamicMaxPeriods, totalPeriods);

        // Log the calculated value for debugging and monitoring purposes
        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Calculated dynamic max periods: %d (Total periods: %d, Price range: %.2f, Current RSOC: %d%%, Required Capacity: %.2f Wh)",
                dynamicMaxPeriods, totalPeriods, priceRange, currentRSOC, requiredCapacity
        ));

        return dynamicMaxPeriods;
    }

    public Set<ChargingSchedule> validateSchedulesForCheaperOptions(Set<ChargingSchedule> schedules) {
        if (schedules == null || schedules.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No schedules provided for validation. Returning an empty set.");
            return Collections.emptySet();
        }

        // Sort by price (ascending) and by start time as a secondary criterion
        List<ChargingSchedule> sortedSchedules = schedules.stream()
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice)
                        .thenComparingLong(ChargingSchedule::getStartTimestamp))
                .toList();

        if (sortedSchedules.isEmpty()) {
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No valid schedules available for validation.");
            return Collections.emptySet();
        }

        Set<ChargingSchedule> validatedSchedules = new LinkedHashSet<>();
        double cheapestPriceSoFar = Double.MAX_VALUE;
        long lastEndTimestamp = -1; // Saves the end timestamp of the last accepted time period

        // Iterate over the sorted time periods and prioritize contiguous favorable blocks
        for (ChargingSchedule schedule : sortedSchedules) {
            boolean isCheapestOrClose = schedule.getPrice() <= cheapestPriceSoFar + 0.3; // Price tolerance
            boolean isConsecutive = lastEndTimestamp == -1 || schedule.getStartTimestamp() == lastEndTimestamp;

            if (isCheapestOrClose || isConsecutive) {
                validatedSchedules.add(schedule);
                cheapestPriceSoFar = schedule.getPrice();
                lastEndTimestamp = schedule.getEndTimestamp();
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Accepted schedule %s - %s (%.2f cents/kWh).",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp())),
                        schedule.getPrice()
                ));
            } else {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Rejected schedule %s - %s (%.2f cents/kWh) as it's neither the cheapest nor consecutive.",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp())),
                        schedule.getPrice()
                ));
            }
        }

        // If no time periods have been selected, select the absolute cheapest as fallback
        if (validatedSchedules.isEmpty() && !sortedSchedules.isEmpty()) {
            ChargingSchedule fallbackSchedule = sortedSchedules.get(0);
            validatedSchedules.add(fallbackSchedule);
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Fallback: Selected the cheapest period %s - %s (%.2f cents/kWh).",
                    dateFormat.format(new Date(fallbackSchedule.getStartTimestamp())),
                    dateFormat.format(new Date(fallbackSchedule.getEndTimestamp())),
                    fallbackSchedule.getPrice()
            ));
        }

        LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                "Validated %d schedules after applying cheaper-future-checks and block prioritization.", validatedSchedules.size()
        ));

        return validatedSchedules;
    }



    /**
     * Checks if the existing task matches the given schedule's timing based on its end timestamp.
     *
     * @param task The existing task.
     * @param schedule The charging schedule to compare against.
     * @return True if the task is up-to-date, false otherwise.
     */
    public boolean isTaskUpToDate(ScheduledFuture<?> task, ChargingSchedule schedule) {

        long actualEndTime = System.currentTimeMillis() + task.getDelay(TimeUnit.MILLISECONDS);
        long expectedEndTime = schedule.getEndTimestamp();
        long margin = 1000;
        return Math.abs(expectedEndTime - actualEndTime) <= margin;
    }


    /**
     * Filters a list of market prices to only include future periods.
     *
     * @param periods The list of market prices to filter.
     * @return A list of market prices that start in the future.
     */
    public List<MarketPrice> filterFuturePeriods(List<MarketPrice> periods) {
        long currentTime = System.currentTimeMillis();
        return periods.stream()
                .filter(price -> price.getStartTimestamp() > currentTime) // Only future periods
                .toList();
    }

    public double calculateDynamicTolerance(List<ChargingSchedule> allSchedules, double rsoc) {
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

    private double calculateVolatilityFactor(double standardDeviation, double averagePrice, int futureScheduleCount) {
        // Maximum multiplier for high volatility
        double maxFactor = 0.03;
        // Minimum multiplier for low volatility
        double minFactor = 0.01;

        // Adjust based on price volatility
        double volatilityAdjustment = standardDeviation / averagePrice;

        // Adjust based on the number of future schedules
        // Add 0.5% if many future schedules exist
        double scheduleAdjustment = futureScheduleCount > 10 ? 0.005 : 0.0;

        double dynamicFactor = minFactor + (volatilityAdjustment * 0.02) + scheduleAdjustment;

        // Clamp the result to ensure it stays within bounds
        return Math.min(maxFactor, Math.max(minFactor, dynamicFactor));
    }

    private double calculateBaseTolerance(double averagePrice, double rsoc) {
        // low price threshold (cents/kWh)
        double lowPriceThreshold = 10.0;
        // high price threshold (cents/kWh)
        double highPriceThreshold = 30.0;

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
        // Default baseline tolerance (5%)
        return 0.05;
    }

    public List<ChargingSchedule> optimizeRemainingSchedules(List<ChargingSchedule> schedules) {
        double minPrice = schedules.stream().mapToDouble(ChargingSchedule::getPrice).min().orElse(Double.MAX_VALUE);
        double rangePrice = schedules.stream().mapToDouble(ChargingSchedule::getPrice).max().orElse(Double.MIN_VALUE) - minPrice;

        return schedules.stream()
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice))
                .filter(schedule -> {
                    double priceDifference = schedule.getPrice() - minPrice;
                    double priceWeight = priceDifference / rangePrice;
                    return priceWeight < 0.3;
                })
                .collect(Collectors.toList());
    }

}
