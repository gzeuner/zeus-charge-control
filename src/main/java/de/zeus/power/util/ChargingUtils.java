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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Copyright 2025 Guido Zeuner - https://tiny-tool.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 *
 * Collection of charging-related helper functions: schedule validation, dynamic thresholds,
 * RSOC projections, and wait/defer heuristics used by the management service.
 */
@Component
public class ChargingUtils {
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private static final double SECONDS_PER_HOUR = 3600.0;
    private static final double MINIMUM_THRESHOLD_OFFSET = 0.05;
    private static final int MINIMUM_CHARGING_PERIODS = 2;

    @Value("${BATTERY_MAX_CAPACITY:10000}")
    private int maxCapacityInWatt; // Wh

    @Value("${battery.target.stateOfCharge:90}")
    private int targetStateOfCharge;

    @Value("${battery.inverter.max.watts:4600}")
    private int chargingPointInWatt;

    @Value("${marketdata.price.flexibility.threshold:10}")
    private double priceFlexibilityThreshold;

    @Value("${marketdata.acceptable.price.cents:15}")
    private int maxAcceptableMarketPriceInCent;

    @Value("${battery.nightChargingIdle:false}")
    private boolean nightChargingIdle;

    @Value("${battery.handback.debounce.ms:60000}")
    private long handBackDebounceMs;

    // Decision parameters for the "wait" heuristic ---
    /** Minimum absolute price advantage (ct/kWh) required before waiting is considered. */
    @Value("${scheduling.wait.min.savings.ct:1.0}")
    private double waitMinSavingsCt;

    /** Minimum relative price advantage (e.g. 0.05 = 5%). */
    @Value("${scheduling.wait.min.savings.pct:0.05}")
    private double waitMinSavingsPct;

    /** Maximum delay in minutes we accept while waiting for a cheaper window. */
    @Value("${scheduling.wait.max.delay.minutes:120}")
    private int waitMaxDelayMinutes;

    /** RSOC safety floor (%) we should not undershoot while waiting. */
    @Value("${battery.rsoc.min.floor:15}")
    private int rsocMinFloor;

    /** Fallback hourly RSOC drop (%) when history is missing or not meaningful. */
    @Value("${battery.rsoc.drop.default.per.hour:3.0}")
    private double defaultRsocDropPerHour;

    private final AtomicLong lastHandBackTs = new AtomicLong(0L);

    @Autowired
    private BatteryManagementService batteryManagementService;

    // ---- validation & calculations (existing) ----

    public boolean isValidSchedule(ChargingSchedule schedule, long currentTime, Set<ChargingSchedule> validatedSchedules, List<MarketPrice> marketPrices) {
        if (isScheduleExpired(schedule, currentTime)) return false;
        if (isScheduleOverpriced(schedule)) return false;
        if (isScheduleOverlapping(schedule, validatedSchedules)) return false;

        // Allow night windows to pass even if they exceed the dynamic threshold slightly (we already filtered by dynamic max price)
        boolean night = isNight(schedule.getStartTimestamp());
        if (!night && exceedsDynamicThreshold(schedule, marketPrices)) return false;
        return true;
    }

    public double calculateRequiredCapacity(int currentRsoc) {
        double currentCapacity = currentRsoc * maxCapacityInWatt / 100.0;
        double targetCapacity = targetStateOfCharge * maxCapacityInWatt / 100.0;
        return targetCapacity - currentCapacity; // Wh
    }

    public int calculateRequiredPeriods(double requiredCapacity, int totalPeriods) {
        int requiredPeriods = (int) Math.ceil(requiredCapacity / (chargingPointInWatt * SECONDS_PER_HOUR));
        return Math.min(requiredPeriods, totalPeriods);
    }

    public List<ChargingSchedule> getFutureChargingSchedules(List<ChargingSchedule> schedules, long currentTime) {
        return schedules.stream()
                .filter(schedule -> schedule.getEndTimestamp() > currentTime)
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());
    }

    public void handleAutomaticModeTransition(long currentTimeMillis) {
        if (batteryManagementService.isManualIdleActive() || batteryManagementService.isNightIdleActive()) return;

        boolean isNight = isNight(currentTimeMillis);
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();

        if (currentRsoc >= targetStateOfCharge) {
            long last = lastHandBackTs.get();
            long now = System.currentTimeMillis();
            if (now - last < handBackDebounceMs) return;

            if (isNight && nightChargingIdle) {
                if (batteryManagementService.resetToAutomaticMode()) lastHandBackTs.set(now);
            } else if (!isNight) {
                if (batteryManagementService.resetToAutomaticMode()) lastHandBackTs.set(now);
            }
        }
    }

    public boolean isNightChargingIdle() { return nightChargingIdle; }

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
            if (currentTime.getHour() < nightEndHour) {
                nightStart = nightStart.minusDays(1);
            } else {
                nightEnd = nightEnd.plusDays(1);
            }
        } else if (nightStartHour == nightEndHour) {
            return true;
        }

        return !currentTime.isBefore(nightStart) && currentTime.isBefore(nightEnd);
    }

    public void createAndLogChargingSchedule(MarketPrice period, ChargingScheduleRepository repository, java.text.DateFormat dateFormat) {
        ChargingSchedule schedule = convertToChargingSchedule(period);
        repository.save(schedule);
        LogFilter.logInfo(ChargingUtils.class, "Added new charging period: {} - {} ({} cents/kWh).",
                dateFormat.format(new Date(period.getStartTimestamp())),
                dateFormat.format(new Date(period.getEndTimestamp())),
                String.format("%.2f", period.getPriceInCentPerKWh()));
    }

    public double calculatePriceRange(List<MarketPrice> periods) {
        if (periods == null || periods.isEmpty()) return 0.0;
        double[] prices = periods.stream().mapToDouble(MarketPrice::getPriceInCentPerKWh).sorted().toArray();
        return prices[prices.length - 1] - prices[0];
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
        double threshold = sorted.get(0).getPriceInCentPerKWh() + 3.0;
        return sorted.stream().filter(p -> p.getPriceInCentPerKWh() <= threshold).limit(maxPeriods).collect(Collectors.toList());
    }

    public boolean exceedsDynamicThreshold(ChargingSchedule schedule, List<MarketPrice> marketPrices) {
        double threshold = calculateDynamicThreshold(marketPrices, priceFlexibilityThreshold);
        return schedule.getPrice() > threshold;
    }

    public double calculateDynamicThreshold(List<MarketPrice> prices, double defaultThreshold) {
        if (prices == null || prices.isEmpty()) return defaultThreshold;
        double medianPrice = calculateMedianPrice(prices);
        double[] stats = calculatePriceStats(prices);
        double range = stats[1] - stats[0];
        double dynamicThreshold = medianPrice + defaultThreshold;

        if (range < 0.5) dynamicThreshold += stats[2] * 0.1;
        else if (range > 3.0) dynamicThreshold -= stats[2] * 0.1;

        return Math.max(dynamicThreshold, stats[0] + MINIMUM_THRESHOLD_OFFSET);
    }

    public double calculateMaxAcceptablePrice(double currentRsoc, double basePrice) {
        double rsocThreshold = targetStateOfCharge * 0.8;
        double priceMultiplier = 1.2;
        return currentRsoc < rsocThreshold ? basePrice * priceMultiplier : basePrice;
    }

    public int calculateDynamicDaytimeThreshold() {
        List<Map.Entry<Long, Integer>> history = batteryManagementService.getRsocHistory();
        int fallback = targetStateOfCharge - 20;
        if (history.size() < 2) return fallback;

        Map.Entry<Long, Integer> oldest = history.get(0);
        Map.Entry<Long, Integer> latest = history.get(history.size() - 1);
        long diffMin = (latest.getKey() - oldest.getKey()) / 60000;
        if (diffMin <= 0) return fallback;

        double dropPerHour = (oldest.getValue() - latest.getValue()) / (diffMin / 60.0);
        int threshold = (int) Math.max(targetStateOfCharge - (dropPerHour * 2), targetStateOfCharge - 30);
        return Math.min(threshold, targetStateOfCharge);
    }

    public boolean adjustForCheaperFutureSchedule(ChargingSchedule currentSchedule, List<ChargingSchedule> schedulesToEvaluate,
                                                  ChargingScheduleRepository repository) {
        if (currentSchedule == null || schedulesToEvaluate == null || schedulesToEvaluate.isEmpty()) return false;

        double dynamicTolerance = clampTolerance(calculateDynamicTolerance(schedulesToEvaluate,
                batteryManagementService.getRelativeStateOfCharge()));
        double priceTolerance = currentSchedule.getPrice() * dynamicTolerance;

        Optional<ChargingSchedule> cheaperFuture = schedulesToEvaluate.stream()
                .filter(s -> s.getStartTimestamp() > currentSchedule.getStartTimestamp())
                .filter(s -> s.getPrice() + priceTolerance < currentSchedule.getPrice())
                .min(Comparator.comparingDouble(ChargingSchedule::getPrice));

        if (cheaperFuture.isPresent() && !repository.existsByStartEndAndPrice(
                cheaperFuture.get().getStartTimestamp(), cheaperFuture.get().getEndTimestamp(), cheaperFuture.get().getPrice())) {
            ChargingSchedule newSchedule = copySchedule(cheaperFuture.get());
            repository.save(newSchedule);
            return true;
        }
        return false;
    }

    public Set<ChargingSchedule> collectAndOptimizeSchedules(List<MarketPrice> periods) {
        if (periods == null || periods.isEmpty()) return Collections.emptySet();
        Set<ChargingSchedule> schedules = new HashSet<>();
        for (MarketPrice p : periods) {
            if (p.getStartTimestamp() < p.getEndTimestamp()) schedules.add(convertToChargingSchedule(p));
        }
        return schedules;
    }

    public void removeExcessChargingPeriods(List<ChargingSchedule> existingSchedules, int periodsToRemove,
                                            ChargingScheduleRepository repository, Map<Long, ScheduledFuture<?>> scheduledTasks) {
        for (int i = 0; i < Math.min(periodsToRemove, existingSchedules.size()); i++) {
            ChargingSchedule s = existingSchedules.get(existingSchedules.size() - 1 - i);
            cancelTask(s.getId(), scheduledTasks);
            repository.delete(s);
        }
    }

    public void cancelTask(long scheduleId, Map<Long, ScheduledFuture<?>> scheduledTasks) {
        ScheduledFuture<?> task = scheduledTasks.remove(scheduleId);
        if (task != null) task.cancel(true);
    }

    public void cleanUpExpiredSchedules(ChargingScheduleRepository repository, Map<Long, ScheduledFuture<?>> scheduledTasks) {
        long now = System.currentTimeMillis();

        List<ChargingSchedule> all = repository.findAll();
        all.stream().filter(s -> s.getEndTimestamp() < now).forEach(repository::delete);

        List<ChargingSchedule> future = all.stream().filter(s -> s.getStartTimestamp() > now).collect(Collectors.toList());
        future.parallelStream().forEach(s -> {
            if (adjustForCheaperFutureSchedule(s, future, repository)) cancelTask(s.getId(), scheduledTasks);
        });

        List<ChargingSchedule> optimizedFuture = optimizeRemainingSchedules(future);
        optimizedFuture.parallelStream().forEach(repository::save);
    }

    public int calculateDynamicMaxChargingPeriods(int totalPeriods, double priceRange, int currentRsoc) {
        double requiredCapacity = calculateRequiredCapacity(currentRsoc);
        if (requiredCapacity <= 0) return 0;

        int basePeriods = (int) Math.ceil(requiredCapacity / (chargingPointInWatt * SECONDS_PER_HOUR));
        basePeriods += priceRange < 0.5 ? 2 : (priceRange > 3.0 ? -1 : 0);
        basePeriods = totalPeriods <= 5 ? Math.min(basePeriods + 1, totalPeriods)
                : totalPeriods > 10 ? Math.max(basePeriods - 1, MINIMUM_CHARGING_PERIODS) : basePeriods;

        return Math.max(MINIMUM_CHARGING_PERIODS, Math.min(basePeriods, totalPeriods));
    }

    public Set<ChargingSchedule> validateSchedulesForCheaperOptions(Set<ChargingSchedule> schedules) {
        if (schedules == null || schedules.isEmpty()) return Collections.emptySet();

        TreeSet<ChargingSchedule> validated = new TreeSet<>(Comparator.comparingDouble(ChargingSchedule::getPrice));
        List<ChargingSchedule> sorted = schedules.stream()
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice).thenComparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());

        AtomicReference<Double> cheapest = new AtomicReference<>(Double.MAX_VALUE);
        validated.addAll(sorted.stream().filter(s -> isNight(s.getStartTimestamp())).limit(3).collect(Collectors.toList()));

        sorted.stream()
                .filter(s -> !isNight(s.getStartTimestamp()))
                .filter(s -> s.getPrice() < cheapest.get())
                .forEach(s -> { validated.add(s); cheapest.set(s.getPrice()); });

        return validated;
    }

    public boolean isTaskUpToDate(ScheduledFuture<?> task, ChargingSchedule schedule) {
        long actualEndTime = System.currentTimeMillis() + task.getDelay(TimeUnit.MILLISECONDS);
        return Math.abs(schedule.getEndTimestamp() - actualEndTime) <= 1000;
    }

    public List<MarketPrice> filterFuturePeriods(List<MarketPrice> periods) {
        long now = System.currentTimeMillis();
        return periods.stream().filter(p -> p.getStartTimestamp() > now).collect(Collectors.toList());
    }

    public double calculateDynamicTolerance(List<ChargingSchedule> allSchedules, double rsoc) {
        double avg = allSchedules.stream().mapToDouble(ChargingSchedule::getPrice).average().orElse(0.0);
        double std = Math.sqrt(allSchedules.stream().mapToDouble(s -> Math.pow(s.getPrice() - avg, 2)).average().orElse(0.0));
        int futureCount = (int) allSchedules.stream().filter(s -> s.getStartTimestamp() > System.currentTimeMillis()).count();
        return calculateBaseTolerance(avg, rsoc) + calculateVolatilityFactor(std, avg, futureCount);
    }

    public List<ChargingSchedule> optimizeRemainingSchedules(List<ChargingSchedule> schedules) {
        double min = schedules.stream().mapToDouble(ChargingSchedule::getPrice).min().orElse(Double.MAX_VALUE);
        double range = schedules.stream().mapToDouble(ChargingSchedule::getPrice).max().orElse(min) - min;
        return schedules.stream()
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice))
                .filter(s -> range == 0 || ((s.getPrice() - min) / range) < 0.3)
                .collect(Collectors.toList());
    }

    /**
     * ======= Decide whether waiting is better than starting now =======
     * Criteria:
     * 1) Sufficient savings: candidate is cheaper than the current window (absolute or percentage threshold).
     * 2) RSOC risk acceptable: projected RSOC by the candidate >= rsocMinFloor.
     * 3) Enough time: accumulated windows from the candidate onward deliver enough energy to reach target SoC.
     */
    public Optional<ChargingSchedule> shouldWaitForCheaperWindow(ChargingSchedule currentWindow,
                                                                 List<ChargingSchedule> futureWindows,
                                                                 BatteryManagementService bms) {
        long now = System.currentTimeMillis();
        long latestStart = now + Math.max(0, waitMaxDelayMinutes) * 60_000L;

        // Candidates: within acceptable delay and cheaper than the current window
        List<ChargingSchedule> candidates = futureWindows.stream()
                .filter(s -> s.getStartTimestamp() >= now && s.getStartTimestamp() <= latestStart)
                .filter(s -> isSignificantlyCheaper(currentWindow.getPrice(), s.getPrice()))
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice)
                        .thenComparingLong(ChargingSchedule::getStartTimestamp))
                .toList();

        if (candidates.isEmpty()) return Optional.empty();

        int currentRsoc = bms.getRelativeStateOfCharge();

        for (ChargingSchedule cand : candidates) {
            // (2) RSOC projection until the candidate start time
            int projectedRsocAtStart = estimateRsocAt(cand.getStartTimestamp(), currentRsoc, bms.getRsocHistory());
            if (projectedRsocAtStart < rsocMinFloor) {
                LogFilter.logDebug(ChargingUtils.class,
                        "Wait-check: reject {} ({}): RSOC projection {}% < floor {}%.",
                        DATE_FORMAT.format(new Date(cand.getStartTimestamp())), cand.getPrice(),
                        projectedRsocAtStart, rsocMinFloor);
                continue;
            }

            // (3) Check if time/power from the candidate window can reach target SoC
            double deficitWh = Math.max(0.0, (targetStateOfCharge - projectedRsocAtStart) * (maxCapacityInWatt / 100.0));
            double deliverableWh = computeDeliverableEnergyFrom(cand, futureWindows);
            boolean timeSufficient = deliverableWh >= deficitWh;

            if (!timeSufficient) {
                LogFilter.logDebug(ChargingUtils.class,
                        "Wait-check: reject {} ({} ct/kWh): deliverableWh {} < deficitWh {}.",
                        DATE_FORMAT.format(new Date(cand.getStartTimestamp())), cand.getPrice(),
                        Math.round(deliverableWh), Math.round(deficitWh));
                continue;
            }

            // All criteria met -> choose to wait
            LogFilter.logInfo(ChargingUtils.class,
                    "Wait-check: choose to WAIT for {} (price {} < {}); RSOC@start={}%, deliverableWh~{}.",
                    DATE_FORMAT.format(new Date(cand.getStartTimestamp())),
                    String.format(Locale.ROOT, "%.2f", cand.getPrice()),
                    String.format(Locale.ROOT, "%.2f", currentWindow.getPrice()),
                    projectedRsocAtStart,
                    Math.round(deliverableWh));
            return Optional.of(cand);
        }

        return Optional.empty();
    }

    // --- Helper functions for shouldWaitForCheaperWindow ---

    private boolean isSignificantlyCheaper(double currentPriceCt, double candidatePriceCt) {
        double absDelta = currentPriceCt - candidatePriceCt;
        double pctDelta = absDelta / Math.max(currentPriceCt, 0.0001);
        return absDelta >= waitMinSavingsCt || pctDelta >= waitMinSavingsPct;
    }

    /**
     * RSOC projection to a target time based on history.
     * If history is missing/short, defaultRsocDropPerHour is used.
     */
    public int estimateRsocAt(long targetEpochMs, int currentRsoc, List<Map.Entry<Long, Integer>> history) {
        if (targetEpochMs <= System.currentTimeMillis()) return currentRsoc;

        double dropPerHour = deriveDropPerHour(history);
        double hours = (targetEpochMs - System.currentTimeMillis()) / 3600_000.0;
        int projected = (int) Math.round(currentRsoc - dropPerHour * hours);
        return Math.max(0, Math.min(100, projected));
    }

    private double deriveDropPerHour(List<Map.Entry<Long, Integer>> history) {
        if (history == null || history.size() < 2) return defaultRsocDropPerHour;

        Map.Entry<Long, Integer> first = history.get(0);
        Map.Entry<Long, Integer> last  = history.get(history.size() - 1);
        long dtMs = Math.max(1L, last.getKey() - first.getKey());
        double dh = dtMs / 3600_000.0;
        if (dh <= 0.05) return defaultRsocDropPerHour; // ~3min

        double drop = Math.max(0.0, first.getValue() - last.getValue());
        // Smoothing: clamp to reasonable bounds
        double perHour = Math.max(0.0, Math.min(20.0, drop / dh));
        // Blend with default a bit to dampen outliers
        return 0.7 * perHour + 0.3 * defaultRsocDropPerHour;
    }

    private double computeDeliverableEnergyFrom(ChargingSchedule startWindow, List<ChargingSchedule> allWindows) {
        // Sum windows from startWindow onward (inclusive) that lie within ~12h after start
        long horizonEnd = startWindow.getStartTimestamp() + 12L * 3600_000L;
        long startTs = startWindow.getStartTimestamp();

        List<ChargingSchedule> seq = allWindows.stream()
                .filter(s -> s.getStartTimestamp() >= startTs && s.getStartTimestamp() <= horizonEnd)
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());

        double totalSeconds = 0.0;
        for (ChargingSchedule s : seq) {
            long dur = Math.max(0L, s.getEndTimestamp() - s.getStartTimestamp());
            totalSeconds += dur / 1000.0;
        }
        // Energy (Wh) = P(W) * t(h)
        return chargingPointInWatt * (totalSeconds / SECONDS_PER_HOUR);
    }

    // ---- helpers (existing) ----

    private static void validateNightHours(int start, int end) {
        if (start < 0 || start > 23 || end < 0 || end > 23) {
            throw new IllegalArgumentException("Invalid night hours: " + start + " to " + end);
        }
    }

    private double calculateMedianPrice(List<MarketPrice> prices) {
        List<Double> sorted = prices.stream().map(MarketPrice::getPriceInCentPerKWh).sorted().toList();
        int n = sorted.size();
        return n % 2 == 0 ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 3.0 : sorted.get(n / 2);
    }

    private double[] calculatePriceStats(List<MarketPrice> prices) {
        double min = prices.stream().mapToDouble(MarketPrice::getPriceInCentPerKWh).min().orElse(0.0);
        double max = prices.stream().mapToDouble(MarketPrice::getPriceInCentPerKWh).max().orElse(0.0);
        double avg = prices.stream().mapToDouble(MarketPrice::getPriceInCentPerKWh).average().orElse(0.0);
        return new double[]{min, max, avg};
    }

    private boolean isScheduleExpired(ChargingSchedule schedule, long currentTime) {
        return schedule.getEndTimestamp() < currentTime;
    }

    private boolean isScheduleOverpriced(ChargingSchedule schedule) {
        return schedule.getPrice() > maxAcceptableMarketPriceInCent;
    }

    private boolean isScheduleOverlapping(ChargingSchedule schedule, Set<ChargingSchedule> validatedSchedules) {
        return validatedSchedules.stream().anyMatch(v ->
                schedule.getStartTimestamp() < v.getEndTimestamp() && schedule.getEndTimestamp() > v.getStartTimestamp());
    }

    private double calculateVolatilityFactor(double stdDev, double avgPrice, int futureCount) {
        double maxFactor = 0.03, minFactor = 0.01;
        double volatilityAdjustment = avgPrice == 0.0 ? 0.0 : (stdDev / avgPrice);
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
