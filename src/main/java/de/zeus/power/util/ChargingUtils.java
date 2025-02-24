package de.zeus.power.util;

import de.zeus.power.config.LogFilter;
import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.repository.ChargingScheduleRepository;
import de.zeus.power.service.BatteryManagementService;
import de.zeus.power.service.ChargingManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
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
 */

/**
 * Utility class providing helper methods for charging-related operations,
 * such as schedule validation, capacity calculations, and period optimization.
 */
@Component
public class ChargingUtils {

    // Date format for logging timestamps
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    // Seconds per hour constant for capacity calculations
    private static final double SECONDS_PER_HOUR = 3600.0;
    // Minimum offset for dynamic threshold adjustments
    private static final double MINIMUM_THRESHOLD_OFFSET = 0.05;
    // Minimum number of charging periods to ensure
    private static final int MINIMUM_CHARGING_PERIODS = 2;

    @Value("${BATTERY_MAX_CAPACITY:10000}")
    private int maxCapacityInWatt;

    @Value("${battery.target.stateOfCharge:90}")
    private int targetStateOfCharge;

    @Value("${battery.chargingPoint}")
    private int chargingPointInWatt;

    @Value("${marketdata.price.flexibility.threshold:10}")
    private double priceFlexibilityThreshold;

    @Value("${marketdata.acceptable.price.cents:15}")
    private int maxAcceptableMarketPriceInCent;

    @Value("${battery.nightChargingIdle:true}")
    private boolean nightChargingIdle;

    @Autowired
    private BatteryManagementService batteryManagementService;

    public boolean isValidSchedule(ChargingSchedule schedule, long currentTime, Set<ChargingSchedule> validatedSchedules, List<MarketPrice> marketPrices) {
        return !isScheduleExpired(schedule, currentTime) &&
                !isScheduleOverpriced(schedule) &&
                !isScheduleOverlapping(schedule, validatedSchedules) &&
                !exceedsDynamicThreshold(schedule, marketPrices);
    }

    public double calculateRequiredCapacity(int currentRsoc) {
        double currentCapacity = currentRsoc * maxCapacityInWatt / 100.0;
        double targetCapacity = targetStateOfCharge * maxCapacityInWatt / 100.0;
        return targetCapacity - currentCapacity;
    }

    public int calculateRequiredPeriods(double requiredCapacity, int totalPeriods) {
        int requiredPeriods = (int) Math.ceil(requiredCapacity / (chargingPointInWatt * SECONDS_PER_HOUR));
        return Math.min(requiredPeriods, totalPeriods);
    }

    public List<ChargingSchedule> getFutureChargingSchedules(List<ChargingSchedule> schedules, long currentTime) {
        List<ChargingSchedule> futureSchedules = schedules.stream()
                .filter(schedule -> schedule.getEndTimestamp() > currentTime)
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());
        if (futureSchedules.isEmpty()) {
            LogFilter.logInfo(ChargingUtils.class, "No future schedules found. Ensure the calling code handles this scenario gracefully.");
        }
        return futureSchedules;
    }

    public void handleAutomaticModeTransition(long currentTimeMillis) {
        boolean isNight = isNight(currentTimeMillis);
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        LogFilter.logInfo(ChargingUtils.class, "Current time: {}, isNight: {}, RSOC: {}%, Target RSOC: {}%",
                currentTimeMillis, isNight, currentRsoc, targetStateOfCharge);

        if (currentRsoc >= targetStateOfCharge) {
            String mode = isNight ? "Nighttime" : "Daytime";
            if (isNight && nightChargingIdle) {
                LogFilter.logInfo(ChargingUtils.class, "{} and RSOC target reached. Setting dynamic charging point to 0.", mode);
                batteryManagementService.setDynamicChargingPoint(0);
            } else if (!isNight) {
                LogFilter.logInfo(ChargingUtils.class, "{} and RSOC target reached. Returning to Automatic Mode.", mode);
                batteryManagementService.resetToAutomaticMode();
            } else {
                LogFilter.logDebug(ChargingUtils.class, "Nighttime with nightChargingIdle=false, no action taken.");
            }
        }
    }

    // Getter for the night charging idle flag
    public boolean isNightChargingIdle() {
        return nightChargingIdle;
    }

    // Setter for the night charging idle flag
    public void setNightChargingIdle(boolean nightChargingIdle) {
        this.nightChargingIdle = nightChargingIdle;
        LogFilter.logInfo(ChargingManagementService.class, "Night charging idle mode set to: {}", nightChargingIdle);
    }

    public static boolean isNight(long currentTimeMillis) {
        int nightStartHour = NightConfig.getNightStartHour();
        int nightEndHour = NightConfig.getNightEndHour();
        validateNightHours(nightStartHour, nightEndHour);

        ZonedDateTime currentTime = Instant.ofEpochMilli(currentTimeMillis).atZone(ZoneId.systemDefault());
        ZonedDateTime nightStart = currentTime.withHour(nightStartHour).withMinute(0).withSecond(0).withNano(0);
        ZonedDateTime nightEnd = currentTime.withHour(nightEndHour).withMinute(0).withSecond(0).withNano(0);

        if (nightStartHour > nightEndHour) {
            nightEnd = currentTime.getHour() < nightEndHour ? nightStart.minusDays(1) : nightEnd.plusDays(1);
        }

        boolean isNight = !currentTime.isBefore(nightStart) && currentTime.isBefore(nightEnd);
        LogFilter.logDebug(ChargingUtils.class, "Night period: Start={}, End={}, Current={} -> isNight={}", nightStart, nightEnd, currentTime, isNight);
        return isNight;
    }

    public void createAndLogChargingSchedule(MarketPrice period, ChargingScheduleRepository repository, DateFormat dateFormat) {
        ChargingSchedule schedule = convertToChargingSchedule(period);
        repository.save(schedule);
        LogFilter.logInfo(ChargingUtils.class, "Added new charging period: {} - {} (%.2f cents/kWh).",
                dateFormat.format(new Date(period.getStartTimestamp())),
                dateFormat.format(new Date(period.getEndTimestamp())), period.getPriceInCentPerKWh());
    }

    public double calculatePriceRange(List<MarketPrice> periods) {
        if (periods == null || periods.isEmpty()) {
            LogFilter.logWarn(ChargingUtils.class, "Market price periods list is empty or null. Returning price range: 0.0.");
            return 0.0;
        }
        double[] prices = periods.stream().mapToDouble(MarketPrice::getPriceInCentPerKWh).sorted().toArray();
        double range = prices[prices.length - 1] - prices[0];
        LogFilter.logInfo(ChargingUtils.class, "Calculated price range: %.2f (min: %.2f, max: %.2f).", range, prices[0], prices[prices.length - 1]);
        return range;
    }

    public ChargingSchedule convertToChargingSchedule(MarketPrice price) {
        ChargingSchedule schedule = new ChargingSchedule();
        schedule.setStartTimestamp(price.getStartTimestamp());
        schedule.setEndTimestamp(price.getEndTimestamp());
        schedule.setPrice(price.getPriceInCentPerKWh());
        return schedule;
    }

    public Optional<MarketPrice> findCheaperFuturePeriod(MarketPrice currentPeriod, List<MarketPrice> allPeriods) {
        return allPeriods.stream()
                .filter(price -> price.getStartTimestamp() > currentPeriod.getEndTimestamp())
                .filter(price -> price.getPriceInCentPerKWh() < currentPeriod.getPriceInCentPerKWh())
                .findFirst();
    }

    public List<MarketPrice> findBestNightPeriods(List<MarketPrice> nighttimePeriods, int maxPeriods) {
        if (nighttimePeriods.isEmpty()) return Collections.emptyList();
        List<MarketPrice> sorted = nighttimePeriods.stream()
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .collect(Collectors.toList());
        double threshold = sorted.get(0).getPriceInCentPerKWh() + 2.0;
        return sorted.stream().filter(p -> p.getPriceInCentPerKWh() <= threshold).limit(maxPeriods).collect(Collectors.toList());
    }

    public boolean exceedsDynamicThreshold(ChargingSchedule schedule, List<MarketPrice> marketPrices) {
        double threshold = calculateDynamicThreshold(marketPrices, priceFlexibilityThreshold);
        if (schedule.getPrice() > threshold) {
            LogFilter.logInfo(ChargingUtils.class, "Skipping schedule exceeding dynamic threshold: {} - {} at %.2f cents/kWh (threshold: %.2f cents/kWh).",
                    DATE_FORMAT.format(new Date(schedule.getStartTimestamp())),
                    DATE_FORMAT.format(new Date(schedule.getEndTimestamp())), schedule.getPrice(), threshold);
            return true;
        }
        return false;
    }

    public double calculateDynamicThreshold(List<MarketPrice> prices, double defaultThreshold) {
        if (prices == null || prices.isEmpty()) return defaultThreshold;
        double medianPrice = calculateMedianPrice(prices);
        double[] stats = calculatePriceStats(prices);
        double range = stats[1] - stats[0];
        double dynamicThreshold = medianPrice + defaultThreshold;

        if (range < 0.5) dynamicThreshold += stats[2] * 0.1;
        else if (range > 2.0) dynamicThreshold -= stats[2] * 0.1;

        dynamicThreshold = Math.max(dynamicThreshold, stats[0] + MINIMUM_THRESHOLD_OFFSET);
        LogFilter.logInfo(ChargingUtils.class, "Calculated dynamic threshold: %.2f (median: %.2f, min: %.2f, max: %.2f, avg: %.2f, range: %.2f)",
                dynamicThreshold, medianPrice, stats[0], stats[1], stats[2], range);
        return dynamicThreshold;
    }

    public double calculateMaxAcceptablePrice(double currentRsoc, double basePrice) {
        double rsocThreshold = targetStateOfCharge * 0.8;
        double priceMultiplier = 1.2;
        double result = currentRsoc < rsocThreshold ? basePrice * priceMultiplier : basePrice;
        LogFilter.logInfo(ChargingUtils.class, "RSOC (%.2f%%) is {} threshold (%.2f%%). {}", currentRsoc,
                currentRsoc < rsocThreshold ? "below" : "above or equal to", rsocThreshold,
                currentRsoc < rsocThreshold ? "Applying price multiplier: " + priceMultiplier : "Using base price: " + basePrice);
        return result;
    }

    public int calculateDynamicDaytimeThreshold() {
        List<Map.Entry<Long, Integer>> history = batteryManagementService.getRsocHistory();
        int fallbackThreshold = targetStateOfCharge - 20;

        if (history.size() < 2) {
            LogFilter.logInfo(ChargingUtils.class, "Insufficient RSOC history data. Using fallback threshold: {}%", fallbackThreshold);
            return fallbackThreshold;
        }

        Map.Entry<Long, Integer> oldest = history.get(0);
        Map.Entry<Long, Integer> latest = history.get(history.size() - 1);
        long timeDiffMinutes = (latest.getKey() - oldest.getKey()) / 60000;

        if (timeDiffMinutes <= 0) {
            LogFilter.logWarn(ChargingUtils.class, "Invalid time difference in RSOC history. Using fallback threshold: {}%", fallbackThreshold);
            return fallbackThreshold;
        }

        double rsocDropPerHour = (oldest.getValue() - latest.getValue()) / (timeDiffMinutes / 60.0);
        int dynamicThreshold = (int) Math.max(targetStateOfCharge - (rsocDropPerHour * 2), targetStateOfCharge - 30);
        dynamicThreshold = Math.min(dynamicThreshold, targetStateOfCharge);

        LogFilter.logInfo(ChargingUtils.class, "RSOC drop rate: %.2f%%/hour. Dynamic daytime threshold calculated: {}%", rsocDropPerHour, dynamicThreshold);
        return dynamicThreshold;
    }

    public boolean adjustForCheaperFutureSchedule(ChargingSchedule currentSchedule, List<ChargingSchedule> schedulesToEvaluate,
                                                  ChargingScheduleRepository repository) {
        if (currentSchedule == null || schedulesToEvaluate == null || schedulesToEvaluate.isEmpty()) {
            LogFilter.logWarn(ChargingUtils.class, "Invalid input: Current schedule or schedules to evaluate is null/empty.");
            return false;
        }

        double dynamicTolerance = clampTolerance(calculateDynamicTolerance(schedulesToEvaluate, batteryManagementService.getRelativeStateOfCharge()));
        double priceTolerance = currentSchedule.getPrice() * dynamicTolerance;

        LogFilter.logDebug(ChargingUtils.class, "Evaluating cheaper future schedules. Current price: %.2f, Dynamic tolerance: %.2f, Price tolerance: %.2f",
                currentSchedule.getPrice(), dynamicTolerance, priceTolerance);

        Optional<ChargingSchedule> cheaperFuture = schedulesToEvaluate.stream()
                .filter(s -> s.getStartTimestamp() > currentSchedule.getStartTimestamp())
                .filter(s -> s.getPrice() + priceTolerance < currentSchedule.getPrice())
                .min(Comparator.comparingDouble(ChargingSchedule::getPrice));

        if (cheaperFuture.isPresent() && !repository.existsByStartEndAndPrice(
                cheaperFuture.get().getStartTimestamp(), cheaperFuture.get().getEndTimestamp(), cheaperFuture.get().getPrice())) {
            ChargingSchedule newSchedule = copySchedule(cheaperFuture.get());
            repository.save(newSchedule);
            LogFilter.logInfo(ChargingUtils.class, "Created new schedule for cheaper future option: {} - {} (price: %.2f cents/kWh).",
                    DATE_FORMAT.format(new Date(newSchedule.getStartTimestamp())),
                    DATE_FORMAT.format(new Date(newSchedule.getEndTimestamp())), newSchedule.getPrice());
            return true;
        }
        LogFilter.logDebug(ChargingUtils.class, "No cheaper future schedules found to adjust the current schedule.");
        return false;
    }

    public Set<ChargingSchedule> collectAndOptimizeSchedules(List<MarketPrice> periods) {
        if (periods == null || periods.isEmpty()) {
            LogFilter.logWarn(ChargingUtils.class, "No periods provided for schedule collection. Returning an empty set.");
            return Collections.emptySet();
        }

        Set<ChargingSchedule> schedules = new HashSet<>();
        for (MarketPrice period : periods) {
            if (period.getStartTimestamp() < period.getEndTimestamp()) {
                ChargingSchedule schedule = convertToChargingSchedule(period);
                if (schedules.add(schedule)) {
                    LogFilter.logDebug(ChargingUtils.class, "Added schedule: Start={}, End={}, Price=%.2f cents/kWh.",
                            DATE_FORMAT.format(new Date(schedule.getStartTimestamp())),
                            DATE_FORMAT.format(new Date(schedule.getEndTimestamp())), schedule.getPrice());
                }
            } else {
                LogFilter.logWarn(ChargingUtils.class, "Invalid period detected: Start={}, End={}. Skipping.",
                        period.getStartTimestamp(), period.getEndTimestamp());
            }
        }
        LogFilter.logInfo(ChargingUtils.class, "Collected and optimized {} charging schedules.", schedules.size());
        return schedules;
    }

    public void removeExcessChargingPeriods(List<ChargingSchedule> existingSchedules, int periodsToRemove,
                                            ChargingScheduleRepository repository, Map<Long, ScheduledFuture<?>> scheduledTasks) {
        for (int i = 0; i < Math.min(periodsToRemove, existingSchedules.size()); i++) {
            ChargingSchedule schedule = existingSchedules.get(existingSchedules.size() - 1 - i);
            cancelTask(schedule.getId(), scheduledTasks);
            repository.delete(schedule);
            LogFilter.logInfo(ChargingUtils.class, "Removed charging period: {} - {}.",
                    DATE_FORMAT.format(new Date(schedule.getStartTimestamp())),
                    DATE_FORMAT.format(new Date(schedule.getEndTimestamp())));
        }
    }

    public void cancelTask(long scheduleId, Map<Long, ScheduledFuture<?>> scheduledTasks) {
        ScheduledFuture<?> task = scheduledTasks.remove(scheduleId);
        if (task != null) {
            task.cancel(true);
            LogFilter.logInfo(ChargingUtils.class, "Cancelled scheduled task for schedule ID: {}", scheduleId);
        }
    }

    public void cleanUpExpiredSchedules(ChargingScheduleRepository repository, Map<Long, ScheduledFuture<?>> scheduledTasks) {
        long currentTime = System.currentTimeMillis();
        LogFilter.logInfo(ChargingUtils.class, "Starting cleanup of expired charging schedules...");

        List<ChargingSchedule> allSchedules = repository.findAll();
        List<ChargingSchedule> expired = allSchedules.stream()
                .filter(s -> s.getEndTimestamp() < currentTime)
                .peek(s -> repository.delete(s))
                .collect(Collectors.toList());
        LogFilter.logInfo(ChargingUtils.class, "Expired schedules cleanup completed. Removed: {}", expired.size());

        List<ChargingSchedule> futureSchedules = allSchedules.stream()
                .filter(s -> s.getStartTimestamp() > currentTime)
                .collect(Collectors.toList());

        futureSchedules.parallelStream().forEach(s -> {
            if (adjustForCheaperFutureSchedule(s, futureSchedules, repository)) {
                cancelTask(s.getId(), scheduledTasks);
                LogFilter.logInfo(ChargingUtils.class, "Adjusted schedule due to cheaper future option: {} - {} (%.2f cents/kWh).",
                        DATE_FORMAT.format(new Date(s.getStartTimestamp())),
                        DATE_FORMAT.format(new Date(s.getEndTimestamp())), s.getPrice());
            }
        });

        List<ChargingSchedule> optimizedFuture = optimizeRemainingSchedules(futureSchedules);
        optimizedFuture.parallelStream().forEach(repository::save);
        LogFilter.logInfo(ChargingUtils.class, "Total schedules retained after cleanup: {}", optimizedFuture.size());
    }

    public int calculateDynamicMaxChargingPeriods(int totalPeriods, double priceRange, int currentRsoc) {
        double requiredCapacity = calculateRequiredCapacity(currentRsoc);
        if (requiredCapacity <= 0) {
            LogFilter.logInfo(ChargingUtils.class, "Battery already at or above target RSOC ({}%). No additional charging required.", targetStateOfCharge);
            return 0;
        }

        int basePeriods = (int) Math.ceil(requiredCapacity / (chargingPointInWatt * SECONDS_PER_HOUR));
        basePeriods += priceRange < 0.5 ? 2 : (priceRange > 2.0 ? -1 : 0);
        basePeriods = totalPeriods <= 5 ? Math.min(basePeriods + 1, totalPeriods) :
                totalPeriods > 10 ? Math.max(basePeriods - 1, MINIMUM_CHARGING_PERIODS) : basePeriods;

        int dynamicMaxPeriods = Math.max(MINIMUM_CHARGING_PERIODS, Math.min(basePeriods, totalPeriods));
        LogFilter.logInfo(ChargingUtils.class, "Calculated dynamic max periods: {} (Total periods: {}, Price range: %.2f, Current RSOC: {}%, Required Capacity: %.2f Wh)",
                dynamicMaxPeriods, totalPeriods, priceRange, currentRsoc, requiredCapacity);
        return dynamicMaxPeriods;
    }

    public Set<ChargingSchedule> validateSchedulesForCheaperOptions(Set<ChargingSchedule> schedules) {
        if (schedules == null || schedules.isEmpty()) {
            LogFilter.logWarn(ChargingUtils.class, "No schedules provided for validation. Returning an empty set.");
            return Collections.emptySet();
        }

        TreeSet<ChargingSchedule> validated = new TreeSet<>(Comparator.comparingDouble(ChargingSchedule::getPrice));
        List<ChargingSchedule> sorted = schedules.stream()
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice).thenComparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());

        AtomicReference<Double> cheapestPriceSoFar = new AtomicReference<>(Double.MAX_VALUE);
        validated.addAll(sorted.stream().filter(s -> isNight(s.getStartTimestamp())).limit(3).collect(Collectors.toList()));

        sorted.stream()
                .filter(s -> !isNight(s.getStartTimestamp()))
                .filter(s -> s.getPrice() < cheapestPriceSoFar.get())
                .forEach(s -> {
                    validated.add(s);
                    cheapestPriceSoFar.set(s.getPrice());
                    LogFilter.logInfo(ChargingUtils.class, "Accepted schedule {} - {} (%.2f cents/kWh).",
                            DATE_FORMAT.format(new Date(s.getStartTimestamp())),
                            DATE_FORMAT.format(new Date(s.getEndTimestamp())), s.getPrice());
                });

        LogFilter.logInfo(ChargingUtils.class, "Validated {} schedules after filtering.", validated.size());
        return validated;
    }

    public boolean isTaskUpToDate(ScheduledFuture<?> task, ChargingSchedule schedule) {
        long actualEndTime = System.currentTimeMillis() + task.getDelay(TimeUnit.MILLISECONDS);
        return Math.abs(schedule.getEndTimestamp() - actualEndTime) <= 1000; // 1-second margin
    }

    public List<MarketPrice> filterFuturePeriods(List<MarketPrice> periods) {
        long currentTime = System.currentTimeMillis();
        return periods.stream().filter(p -> p.getStartTimestamp() > currentTime).collect(Collectors.toList());
    }

    public double calculateDynamicTolerance(List<ChargingSchedule> allSchedules, double rsoc) {
        double averagePrice = allSchedules.stream().mapToDouble(ChargingSchedule::getPrice).average().orElse(0.0);
        double stdDev = Math.sqrt(allSchedules.stream().mapToDouble(s -> Math.pow(s.getPrice() - averagePrice, 2)).average().orElse(0.0));
        int futureCount = (int) allSchedules.stream().filter(s -> s.getStartTimestamp() > System.currentTimeMillis()).count();
        return calculateBaseTolerance(averagePrice, rsoc) + calculateVolatilityFactor(stdDev, averagePrice, futureCount);
    }

    public List<ChargingSchedule> optimizeRemainingSchedules(List<ChargingSchedule> schedules) {
        double minPrice = schedules.stream().mapToDouble(ChargingSchedule::getPrice).min().orElse(Double.MAX_VALUE);
        double range = schedules.stream().mapToDouble(ChargingSchedule::getPrice).max().orElse(minPrice) - minPrice;
        return schedules.stream()
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice))
                .filter(s -> range == 0 || ((s.getPrice() - minPrice) / range) < 0.3)
                .collect(Collectors.toList());
    }

    // Helper methods

    private static void validateNightHours(int start, int end) {
        if (start < 0 || start > 23 || end < 0 || end > 23) {
            throw new IllegalArgumentException("Invalid night hours: " + start + " to " + end);
        }
    }

    private double calculateMedianPrice(List<MarketPrice> prices) {
        List<Double> sorted = prices.stream().map(MarketPrice::getPriceInCentPerKWh).sorted().collect(Collectors.toList());
        int size = sorted.size();
        return size % 2 == 0 ? (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0 : sorted.get(size / 2);
    }

    private double[] calculatePriceStats(List<MarketPrice> prices) {
        double min = prices.stream().mapToDouble(MarketPrice::getPriceInCentPerKWh).min().orElse(0.0);
        double max = prices.stream().mapToDouble(MarketPrice::getPriceInCentPerKWh).max().orElse(0.0);
        double avg = prices.stream().mapToDouble(MarketPrice::getPriceInCentPerKWh).average().orElse(0.0);
        return new double[]{min, max, avg};
    }

    private boolean isScheduleExpired(ChargingSchedule schedule, long currentTime) {
        if (schedule.getEndTimestamp() < currentTime) {
            LogFilter.logInfo(ChargingUtils.class, "Skipping expired schedule: {} - {} (%.2f cents/kWh).",
                    DATE_FORMAT.format(new Date(schedule.getStartTimestamp())),
                    DATE_FORMAT.format(new Date(schedule.getEndTimestamp())), schedule.getPrice());
            return true;
        }
        return false;
    }

    private boolean isScheduleOverpriced(ChargingSchedule schedule) {
        if (schedule.getPrice() > maxAcceptableMarketPriceInCent) {
            LogFilter.logInfo(ChargingUtils.class, "Skipping overpriced schedule: {} - {} at %.2f cents/kWh (max acceptable: {} cents/kWh).",
                    DATE_FORMAT.format(new Date(schedule.getStartTimestamp())),
                    DATE_FORMAT.format(new Date(schedule.getEndTimestamp())), schedule.getPrice(), maxAcceptableMarketPriceInCent);
            return true;
        }
        return false;
    }

    private boolean isScheduleOverlapping(ChargingSchedule schedule, Set<ChargingSchedule> validatedSchedules) {
        boolean overlaps = validatedSchedules.stream().anyMatch(v ->
                schedule.getStartTimestamp() < v.getEndTimestamp() && schedule.getEndTimestamp() > v.getStartTimestamp());
        if (overlaps) {
            LogFilter.logInfo(ChargingUtils.class, "Skipping overlapping schedule: {} - {} (%.2f cents/kWh).",
                    DATE_FORMAT.format(new Date(schedule.getStartTimestamp())),
                    DATE_FORMAT.format(new Date(schedule.getEndTimestamp())), schedule.getPrice());
            return true;
        }
        return false;
    }

    private double calculateVolatilityFactor(double stdDev, double avgPrice, int futureCount) {
        double maxFactor = 0.03, minFactor = 0.01;
        double volatilityAdjustment = stdDev / avgPrice;
        double scheduleAdjustment = futureCount > 10 ? 0.005 : 0.0;
        return Math.min(maxFactor, Math.max(minFactor, minFactor + (volatilityAdjustment * 0.02) + scheduleAdjustment));
    }

    private double calculateBaseTolerance(double avgPrice, double rsoc) {
        if (avgPrice <= 10.0) return 0.07;
        if (avgPrice >= 30.0) return 0.03;
        if (rsoc < 20.0) return 0.02;
        if (rsoc > 80.0) return 0.06;
        return 0.05;
    }

    private double clampTolerance(double tolerance) {
        return tolerance < 0.0 || tolerance > 1.0 ? 0.05 : tolerance;
    }

    private ChargingSchedule copySchedule(ChargingSchedule source) {
        ChargingSchedule copy = new ChargingSchedule();
        copy.setStartTimestamp(source.getStartTimestamp());
        copy.setEndTimestamp(source.getEndTimestamp());
        copy.setPrice(source.getPrice());
        return copy;
    }
}