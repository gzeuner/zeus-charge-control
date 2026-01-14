package de.zeus.power.service;

import de.zeus.power.config.BatteryProperties;
import de.zeus.power.config.LogFilter;
import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.event.MarketPricesUpdatedEvent;
import de.zeus.power.repository.ChargingScheduleRepository;
import de.zeus.power.repository.MarketPriceRepository;
import de.zeus.power.util.ChargingUtils;
import de.zeus.power.util.NightConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Copyright 2025 Guido Zeuner - https://tiny-tool.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 *
 * Coordinates optimization of charging periods based on market prices, RSOC and weather.
 * Handles scheduling (start/stop/HOLD tasks) and keeps persisted plans in sync.
 */
@Service
public class ChargingManagementService {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private static final String EVERY_HOUR_CRON = "0 0 * * * ?";
    private static final long END_OF_NIGHT_RESET_ID = -1L;
    private static final long START_OF_NIGHT_IDLE_ID = -2L;

    @Autowired private ChargingScheduleRepository chargingScheduleRepository;
    @Autowired private MarketPriceRepository marketPriceRepository;
    @Autowired private BatteryManagementService batteryManagementService;
    @Autowired private TaskScheduler taskScheduler;
    @Autowired private ChargingUtils chargingUtils;
    @Autowired(required = false) private BatteryProperties batteryProperties; // nightPauseWatts & holdIntervalSeconds
    @Autowired private WeatherForecastService weatherForecastService;

    @Value("${battery.target.stateOfCharge:90}")
    private int targetStateOfCharge;

    @Value("${marketdata.acceptable.price.cents:15}")
    private int maxAcceptableMarketPriceInCent;

    @Value("${marketdata.price.flexibility.threshold:10}")
    private double priceFlexibilityThreshold;

    @Value("${nighttime.max.periods:2}")
    private int maxNighttimePeriods;

    @Value("${weather.wait.max.minutes:120}")
    private int weatherWaitMaxMinutes;

    @Value("${weather.wait.min.rsoc:45}")
    private int weatherWaitMinRsoc;

    @Value("${scheduling.catchup.enabled:true}")
    private boolean catchupEnabled;

    /** Percent points below target at which a mid-window catch-up start is allowed. */
    @Value("${scheduling.catchup.rsoc.drop.pct:5}")
    private int catchupRsocDropPct;

    /** Allow catch-up starts only within this many minutes after window start. */
    @Value("${scheduling.catchup.max.minutes.from.start:90}")
    private int catchupMaxMinutesFromStart;

    @Value("${scheduling.wait.max.defer.hours:6}")
    private int waitMaxDeferHours;

    @Value("${scheduling.wait.min.price.diff.cents:0.5}")
    private double waitMinPriceDiffCents;

    @Value("${scheduling.wait.relative.drop.pct:5.0}")
    private double waitRelativeDropPct;

    @Value("${scheduling.night.latestStart.offset.minutes:60}")
    private int nightLatestStartOffsetMinutes;

    @Value("${scheduling.daytime.lookahead.hours:12}")
    private int daytimeLookaheadHours;

    @Value("${battery.pv.only.enabled:true}")
    private boolean pvOnlyEnabled;

    // --- Holds & Setpoints ---
    @Value("${battery.manual.hold.ms:900000}")              // 15 min (Forced-Charge)
    private long manualHoldMs;

    @Value("${battery.manual.idle.hold.ms:900000}")         // 15 min (Manual Idle 1W)
    private long manualIdleHoldMs;

    @Value("${battery.night.pause.watts:1}")                // 1 W idle setpoint
    private int nightPauseWatts;

    /** Interval to refresh >0W setpoints so EMS does not reclaim control. */
    @Value("${battery.setpoint.refresh.ms:0}")
    private long setpointRefreshMsConfigured;

    // Flag indicating whether manual idle is active
    private final AtomicBoolean manualIdleHoldActive = new AtomicBoolean(false);
    private ScheduledFuture<?> manualHoldHeartbeat;


    private int holdIntervalSeconds() { return batteryProperties != null ? batteryProperties.getHoldIntervalSeconds() : 15; }
    private int nightPauseWatts()     { return batteryProperties != null ? batteryProperties.getNightPauseWatts()     : 1; }
    private long setpointRefreshMs() {
        if (setpointRefreshMsConfigured > 0) return setpointRefreshMsConfigured;
        return Math.max(5, holdIntervalSeconds()) * 1000L;
    }

    private final List<ChargingSchedule> daytimeBuffer = new CopyOnWriteArrayList<>();
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> holdTasks = new ConcurrentHashMap<>();
    private final Set<Long> catchupAttempts = ConcurrentHashMap.newKeySet();

    // -------- events & schedulers

    @EventListener
    public void onMarketPricesUpdated(MarketPricesUpdatedEvent event) {
        LogFilter.logInfo(ChargingManagementService.class, "Market prices updated event received. Recalculating charging schedule...");
        optimizeChargingProcess();
    }

    @Scheduled(cron = EVERY_HOUR_CRON)
    public void scheduledOptimizeChargingSchedule() {
        chargingUtils.cleanUpExpiredSchedules(chargingScheduleRepository, scheduledTasks);
        if (shouldOptimize()) {
            optimizeChargingProcess();
        }
    }


    /** RSOC/mode monitor; keeps FE-forced alive and respects the RSOC cap. */
    @Scheduled(fixedRateString = "${battery.automatic.mode.check.interval:60000}")
    private void monitorRsocAndHandleMode() {
        long now = System.currentTimeMillis();
        int rsoc = batteryManagementService.getRelativeStateOfCharge();

        // FE: "Manual-Hold" (forced charging)
        if (batteryManagementService.isForcedChargingActive()) {
            ensureManualHoldHeartbeat();
        } else {
            cancelManualHoldHeartbeat();
        }

        // Keep manually activated idle (1W) alive
        if (batteryManagementService.isManualIdleActive()) {
            // enforce ~1W setpoint
            boolean okTiny = batteryManagementService.pauseWithTinySetpoint();
            if (okTiny) {
                // Extend MANUAL lease continuously
                batteryManagementService.startManualIdleHold();
                LogFilter.logDebug(ChargingManagementService.class,
                        "Manual Idle Hold extended (1W setpoint confirmed).");
            } else {
                LogFilter.logInfo(ChargingManagementService.class,
                        "Manual Idle Hold failed to set 1W -> return control to EM.");
                batteryManagementService.setManualIdleActive(false);
                batteryManagementService.resetToAutomaticMode(true);
            }
        }


        chargingUtils.handleAutomaticModeTransition(now);
        batteryManagementService.updateRsocHistory(now, rsoc);
        tryCatchupStart(now, rsoc);
    }

    /** Allow mid-window start when RSOC falls below a hysteresis threshold while price window is still active. */
    private void tryCatchupStart(long now, int rsoc) {
        if (!catchupEnabled) return;
        if (batteryManagementService.isOwnerProtected()) return; // respect manual/other holds

        int threshold = Math.max(0, targetStateOfCharge - Math.max(0, catchupRsocDropPct));
        if (rsoc >= threshold) return;

        Optional<ChargingSchedule> active = chargingScheduleRepository.findAll().stream()
                .filter(s -> now >= s.getStartTimestamp() && now < s.getEndTimestamp())
                .findFirst();
        if (active.isEmpty()) return;

        ChargingSchedule schedule = active.get();
        long catchupHorizon = schedule.getStartTimestamp() + Math.max(1, catchupMaxMinutesFromStart) * 60_000L;
        if (now > catchupHorizon) return; // outside allowed catch-up window
        if (catchupAttempts.contains(schedule.getId())) return; // already tried for this window

        catchupAttempts.add(schedule.getId());
        boolean started = false;
        try {
            started = batteryManagementService.initCharging(false);
        } catch (Exception ex) {
            LogFilter.logWarn(ChargingManagementService.class, "Catch-up start failed: {}", ex.getMessage());
        }

        LogFilter.logInfo(ChargingManagementService.class,
                "{} catch-up start inside window {} - {} (RSOC {}%, threshold {}%, horizon +{} min).",
                started ? "Triggered" : "Skipped/failed",
                DATE_FORMAT.format(new Date(schedule.getStartTimestamp())),
                DATE_FORMAT.format(new Date(schedule.getEndTimestamp())),
                rsoc, threshold, catchupMaxMinutesFromStart);
    }

    // -------- optimize flow

    private void optimizeChargingSchedule() {
        Set<ChargingSchedule> optimized = new HashSet<>();
        optimized.addAll(optimizeNighttimeCharging());
        optimized.addAll(optimizeDaytimeCharging());

        int dynamicThreshold = chargingUtils.calculateDynamicDaytimeThreshold();
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        long currentTime = System.currentTimeMillis();

        if (!ChargingUtils.isNight(currentTime) && currentRsoc <= dynamicThreshold) {
            if (currentRsoc < targetStateOfCharge) {
                optimized.addAll(getFutureDaytimeSchedules(currentTime));
            }
        }
        synchronizeSchedules(optimized);
    }

    private void optimizeChargingProcess() {
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        List<MarketPrice> marketPrices = marketPriceRepository.findAll();

        if (currentRsoc >= targetStateOfCharge) {
            removeAllPlannedChargingPeriods();
            return;
        }

        double dynamicMaxPrice = calculateDynamicMaxPrice(marketPrices);
        adjustChargingPeriodsDynamically(currentRsoc, marketPrices.size(), marketPrices, dynamicMaxPrice);
        bufferDaytimeCharging(marketPrices);
        optimizeChargingSchedule();
        scheduleEndOfNightResetTask();
    }

    // -------- buffering / thresholds

    private void bufferDaytimeCharging(List<MarketPrice> marketPrices) {
        long currentTime = System.currentTimeMillis();
        List<MarketPrice> daytimePeriods = getDaytimePeriods(currentTime);
        daytimeBuffer.clear();
        if (daytimePeriods.isEmpty()) return;

        double dynamicMaxPrice = calculateDynamicMaxPrice(marketPrices);
        double threshold = chargingUtils.calculateDynamicThreshold(daytimePeriods, priceFlexibilityThreshold);
        double maxAcceptablePrice = Math.min(dynamicMaxPrice,
                chargingUtils.calculateMaxAcceptablePrice(batteryManagementService.getRelativeStateOfCharge(), maxAcceptableMarketPriceInCent));

        int dynamicMaxPeriods = chargingUtils.calculateDynamicMaxChargingPeriods(daytimePeriods.size(),
                chargingUtils.calculatePriceRange(daytimePeriods), batteryManagementService.getRelativeStateOfCharge());

        Set<ChargingSchedule> validated = daytimePeriods.stream()
                .filter(p -> p.getPriceInCentPerKWh() <= threshold && p.getPriceInCentPerKWh() <= maxAcceptablePrice)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(dynamicMaxPeriods)
                .map(chargingUtils::convertToChargingSchedule)
                .filter(s -> chargingUtils.isValidSchedule(s, currentTime, new HashSet<>(daytimeBuffer), daytimePeriods))
                .collect(Collectors.toSet());

        daytimeBuffer.addAll(validated);
    }

    private double calculateDynamicMaxPrice(List<MarketPrice> marketPrices) {
        if (marketPrices == null || marketPrices.isEmpty()) return maxAcceptableMarketPriceInCent;
        double median = marketPrices.stream().mapToDouble(MarketPrice::getPriceInCentPerKWh).sorted()
                .skip(marketPrices.size() / 2).findFirst().orElse(maxAcceptableMarketPriceInCent);
        return Math.min(median * 1.2, maxAcceptableMarketPriceInCent);
    }

    private List<MarketPrice> getDaytimePeriods(long currentTime) {
        long toleranceWindow = currentTime + (daytimeLookaheadHours * 60L * 60L * 1000L);
        return marketPriceRepository.findAll().stream()
                .filter(p -> !ChargingUtils.isNight(p.getStartTimestamp())
                        && p.getStartTimestamp() > currentTime
                        && p.getStartTimestamp() <= toleranceWindow)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .collect(Collectors.toList());
    }

    /** Reintroduced: future daytime schedules come from the daytime buffer. */
    private List<ChargingSchedule> getFutureDaytimeSchedules(long currentTime) {
        List<ChargingSchedule> future = daytimeBuffer.stream()
                .filter(s -> s.getStartTimestamp() > currentTime && !ChargingUtils.isNight(s.getStartTimestamp()))
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());
        LogFilter.logInfo(ChargingManagementService.class, "Added {} future schedules from daytime buffer.", future.size());
        return future;
    }

    // -------- add/remove & synchronize

    private boolean shouldOptimize() {
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        long currentTime = System.currentTimeMillis();
        boolean hasFuture = chargingScheduleRepository.findAll().stream().anyMatch(s -> s.getEndTimestamp() > currentTime);
        return !hasFuture || currentRsoc < targetStateOfCharge;
    }

    private void removeAllPlannedChargingPeriods() {
        chargingScheduleRepository.findAll().stream()
                .filter(s -> s.getEndTimestamp() > System.currentTimeMillis())
                .forEach(s -> {
                    cancelHoldForSchedule(s.getId());
                    chargingUtils.cancelTask(s.getId(), scheduledTasks);
                    catchupAttempts.remove(s.getId());
                    chargingScheduleRepository.delete(s);
                });
    }

    public void schedulePlannedCharging() {
        chargingScheduleRepository.findAll().stream()
                .filter(s -> s.getStartTimestamp() > System.currentTimeMillis())
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .forEach(this::scheduleChargingTask);
    }

    public void scheduleStopPlannedCharging() {
        chargingScheduleRepository.findAll().stream()
                .filter(s -> s.getStartTimestamp() > System.currentTimeMillis())
                .sorted(Comparator.comparingLong(ChargingSchedule::getEndTimestamp))
                .forEach(this::scheduleStopTask);
    }

    private List<ChargingSchedule> optimizeDaytimeCharging() {
        List<MarketPrice> day = chargingUtils.filterFuturePeriods(marketPriceRepository.findAll()).stream()
                .filter(p -> !ChargingUtils.isNight(p.getStartTimestamp()))
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .collect(Collectors.toList());
        if (day.isEmpty()) return Collections.emptyList();

        double threshold = chargingUtils.calculateDynamicThreshold(day, priceFlexibilityThreshold);
        double maxAcceptablePrice = chargingUtils.calculateMaxAcceptablePrice(
                batteryManagementService.getRelativeStateOfCharge(), maxAcceptableMarketPriceInCent);

        int dynamicMaxPeriods = chargingUtils.calculateDynamicMaxChargingPeriods(day.size(),
                chargingUtils.calculatePriceRange(day), batteryManagementService.getRelativeStateOfCharge());

        long now = System.currentTimeMillis();
        long toleranceWindow = now + (daytimeLookaheadHours * 60L * 60L * 1000L);

        return day.stream()
                .filter(p -> p.getPriceInCentPerKWh() <= threshold && p.getPriceInCentPerKWh() <= maxAcceptablePrice)
                .filter(p -> p.getStartTimestamp() <= toleranceWindow)
                .filter(p -> chargingUtils.findCheaperFuturePeriod(p, day).isEmpty())
                .limit(dynamicMaxPeriods)
                .map(chargingUtils::convertToChargingSchedule)
                .collect(Collectors.toList());
    }

    private List<ChargingSchedule> optimizeNighttimeCharging() {
        int rsoc = batteryManagementService.getRelativeStateOfCharge();
        if (rsoc >= targetStateOfCharge) return Collections.emptyList();

        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zone);
        int nightStartHour = NightConfig.getNightStartHour();
        int nightEndHour = NightConfig.getNightEndHour();

        LocalDateTime nightStartTime = now.with(LocalTime.of(nightStartHour, 0));
        LocalDateTime nightEndTime = now.with(LocalTime.of(nightEndHour, 0));

        if (nightStartHour > nightEndHour) { // spans midnight
            if (now.getHour() < nightEndHour) {
                nightStartTime = nightStartTime.minusDays(1);
            } else {
                nightEndTime = nightEndTime.plusDays(1);
            }
        } else if (nightStartHour == nightEndHour) {
            nightEndTime = nightEndTime.plusDays(1);
        } else {
            if (!now.isBefore(nightEndTime)) {
                nightStartTime = nightStartTime.plusDays(1);
                nightEndTime = nightEndTime.plusDays(1);
            }
        }

        long nightStartTimestamp = nightStartTime.atZone(zone).toInstant().toEpochMilli();
        long nightEndTimestamp = nightEndTime.atZone(zone).toInstant().toEpochMilli();
        long latestAcceptableStart = nightEndTimestamp - Math.max(0, nightLatestStartOffsetMinutes) * 60_000L;
        long currentTime = System.currentTimeMillis();

        List<MarketPrice> all = marketPriceRepository.findAll();
        List<MarketPrice> night = all.stream()
                .filter(p -> p.getEndTimestamp() > currentTime)
                .filter(p -> p.getStartTimestamp() >= nightStartTimestamp && p.getStartTimestamp() < nightEndTimestamp)
                .collect(Collectors.toList());
        if (night.isEmpty()) return Collections.emptyList();

        double dynamicMaxPrice = calculateDynamicMaxPrice(all);
        int requiredPeriods = chargingUtils.calculateRequiredPeriods(
                chargingUtils.calculateRequiredCapacity(rsoc), all.size());
        int periodsToPlan = Math.min(Math.max(requiredPeriods, 1), maxNighttimePeriods);

        Comparator<MarketPrice> comparator = Comparator
                .comparingDouble(MarketPrice::getPriceInCentPerKWh)
                .thenComparingLong(MarketPrice::getStartTimestamp);

        List<MarketPrice> preferred = night.stream()
                .filter(p -> p.getPriceInCentPerKWh() <= dynamicMaxPrice)
                .filter(p -> p.getStartTimestamp() <= latestAcceptableStart)
                .sorted(comparator)
                .limit(periodsToPlan)
                .collect(Collectors.toList());

        if (preferred.isEmpty()) { // fallback: allow late starts if nothing else fits
            preferred = night.stream()
                    .filter(p -> p.getPriceInCentPerKWh() <= dynamicMaxPrice)
                    .filter(p -> p.getStartTimestamp() < nightEndTimestamp)
                    .sorted(comparator)
                    .limit(periodsToPlan)
                    .collect(Collectors.toList());
        }

        LogFilter.logInfo(ChargingManagementService.class,
                "Night window {} -> {} (latest start {}), candidates={}, selected={}",
                DATE_FORMAT.format(new Date(nightStartTimestamp)),
                DATE_FORMAT.format(new Date(nightEndTimestamp)),
                DATE_FORMAT.format(new Date(latestAcceptableStart)),
                night.size(),
                preferred.size());

        return preferred.stream()
                .map(chargingUtils::convertToChargingSchedule)
                .collect(Collectors.toList());
    }

    private void adjustChargingPeriodsDynamically(int currentRsoc, int totalPeriods,
                                                  List<MarketPrice> marketPrices, double dynamicMaxPrice) {
        double requiredCapacity = chargingUtils.calculateRequiredCapacity(currentRsoc);
        if (requiredCapacity <= 0) { removeAllPlannedChargingPeriods(); return; }

        List<ChargingSchedule> existing = chargingUtils.getFutureChargingSchedules(
                chargingScheduleRepository.findAll(), System.currentTimeMillis());
        int requiredPeriods = chargingUtils.calculateRequiredPeriods(requiredCapacity, totalPeriods);
        int excess = existing.size() - requiredPeriods;
        if (excess > 0) {
            chargingUtils.removeExcessChargingPeriods(existing, excess, chargingScheduleRepository, scheduledTasks);
        }

        int missing = requiredPeriods - existing.size();
        if (missing > 0) addAdditionalChargingPeriodsExcludingNighttime(marketPrices, missing, existing, dynamicMaxPrice);
    }

    private void addAdditionalChargingPeriodsExcludingNighttime(List<MarketPrice> marketPrices, int periodsToAdd,
                                                                List<ChargingSchedule> existingSchedules, double dynamicMaxPrice) {
        long currentTime = System.currentTimeMillis();
        long toleranceWindow = currentTime + (daytimeLookaheadHours * 60L * 60L * 1000L);

        List<MarketPrice> available = marketPrices.stream()
                .filter(p -> p.getStartTimestamp() > currentTime && p.getStartTimestamp() <= toleranceWindow)
                .filter(p -> !ChargingUtils.isNight(p.getStartTimestamp()))
                .filter(p -> p.getPriceInCentPerKWh() <= dynamicMaxPrice)
                .filter(p -> existingSchedules.stream().noneMatch(s -> Objects.equals(s.getStartTimestamp(), p.getStartTimestamp())))
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(periodsToAdd)
                .collect(Collectors.toList());

        available.forEach(p -> chargingUtils.createAndLogChargingSchedule(p, chargingScheduleRepository, DATE_FORMAT));
    }

    // -------- run/start/stop tasks (HOLD via scheduleHoldTick)

    private void executeChargingTask(ChargingSchedule schedule) {
        Date startTime = new Date(schedule.getStartTimestamp());
        Date endTime = new Date(schedule.getEndTimestamp());
        LogFilter.logInfo(ChargingManagementService.class, "Window start: {} - {}", DATE_FORMAT.format(startTime), DATE_FORMAT.format(endTime));

        // Check before start if waiting for a much cheaper near-term window makes sense
        try {
            long now = System.currentTimeMillis();
            long finalNow = now;
            List<ChargingSchedule> future = chargingScheduleRepository.findAll().stream()
                    .filter(s -> s.getStartTimestamp() >= finalNow)
                    .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                    .collect(Collectors.toList());

            Optional<ChargingSchedule> waitFor = chargingUtils.shouldWaitForCheaperWindow(schedule, future, batteryManagementService);
            if (waitFor.isPresent()) {
                ChargingSchedule cand = waitFor.get();
                boolean isNight = ChargingUtils.isNight(schedule.getStartTimestamp());
                boolean candidateTooLateForNight = isNight && cand.getStartTimestamp() > calculateLatestNightStart(schedule.getStartTimestamp());
                if (candidateTooLateForNight) {
                    LogFilter.logInfo(ChargingManagementService.class,
                            "Will start charging at {} ({} ct/kWh). Cheaper window {} ({} ct/kWh) is after the latest acceptable night start.",
                            DATE_FORMAT.format(startTime), schedule.getPrice(),
                            DATE_FORMAT.format(new Date(cand.getStartTimestamp())), cand.getPrice());
                } else {
                    /* price-based waiting chosen */
                    LogFilter.logInfo(ChargingManagementService.class,
                            "Will NOT start charging at {} ({} ct/kWh). Waiting for cheaper window {} ({} ct/kWh).",
                            DATE_FORMAT.format(startTime), schedule.getPrice(),
                            DATE_FORMAT.format(new Date(cand.getStartTimestamp())), cand.getPrice());
                    // Important: do nothing here; do not take ownership, EM keeps control.
                    return;
                }
            }
            if (!waitFor.isPresent()) {
                // Fine-grained: defer to a clearly cheaper daytime slot within the configured horizon
                try {
                    if (!ChargingUtils.isNight(startTime.getTime())) {
                        long now2 = System.currentTimeMillis();
                        long horizon = now2 + Math.max(1, waitMaxDeferHours) * 3_600_000L;

                        java.util.List<ChargingSchedule> nearFuture = future.stream()
                                .filter(s -> s.getStartTimestamp() > now2 && s.getStartTimestamp() <= horizon)
                                .filter(s -> !ChargingUtils.isNight(s.getStartTimestamp()))
                                .sorted(java.util.Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                                .collect(java.util.stream.Collectors.toList());

                        if (!nearFuture.isEmpty()) {
                            double pNow = schedule.getPrice();
                            ChargingSchedule best = nearFuture.stream()
                                    .min(java.util.Comparator.comparingDouble(ChargingSchedule::getPrice))
                                    .orElse(null);
                            if (best != null) {
                                double absDrop = pNow - best.getPrice();
                                double relDropPct = (absDrop / Math.max(0.01, pNow)) * 100.0;

                                boolean clearlyCheaper = (absDrop >= waitMinPriceDiffCents) || (relDropPct >= waitRelativeDropPct);
                                if (clearlyCheaper) {
                                    LogFilter.logInfo(ChargingManagementService.class,
                                            "Defer: {} ct/kWh now vs {} ct/kWh at {} (delta {} ct, {}%). Waiting for cheaper slot.",
                                            pNow, best.getPrice(),
                                            DATE_FORMAT.format(new java.util.Date(best.getStartTimestamp())),
                                            String.format(java.util.Locale.ROOT, "%.2f", absDrop),
                                            String.format(java.util.Locale.ROOT, "%.1f", relDropPct));
                                    return;
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    LogFilter.logWarn(ChargingManagementService.class, "Defer-to-cheaper decision failed: {}", ex.getMessage());
                }

                try {
                    now = System.currentTimeMillis();
                    long latestStart = now + Math.max(0, weatherWaitMaxMinutes) * 60_000L;

                    // Only consider weather if the current start lies outside night hours
                    if (!ChargingUtils.isNight(startTime.getTime()) && weatherForecastService.isEnabled()) {
                        int rsoc = batteryManagementService.getRelativeStateOfCharge();
                        boolean rsocHighEnough = rsoc >= Math.max(0, weatherWaitMinRsoc);
                        if (rsocHighEnough) {
                            java.util.Optional<Long> sunny = weatherForecastService.findSunnySlot(now, latestStart);
                            if (sunny.isPresent()) {
                                long sunnySlot = sunny.get();
                                boolean withinShortWindow = sunnySlot <= latestStart;
                                if (withinShortWindow) {
                                    LogFilter.logInfo(ChargingManagementService.class,
                                            "Will NOT start charging at {} ({} ct/kWh). Weather suggests sunshine around {} — waiting for PV.",
                                            DATE_FORMAT.format(startTime), schedule.getPrice(),
                                            DATE_FORMAT.format(new java.util.Date(sunnySlot)));
                                    return; // do nothing, keep control with EM (0 W)
                                }
                            }
                        }
                    }

                } catch (Exception ex) {
                    LogFilter.logWarn(ChargingManagementService.class, "Weather wait-decision failed: {}", ex.getMessage());
                }
            }

        } catch (Exception ex) {
            LogFilter.logWarn(ChargingManagementService.class, "Wait-decision check failed: {}", ex.getMessage());
        }

        try {
            boolean keep = batteryManagementService.scheduleHoldTick();
            if (!keep) return; // e.g. RSOC >= target and outside window -> do not start
        } catch (Exception ex) {
            LogFilter.logWarn(ChargingManagementService.class, "Initial HOLD tick failed: {}", ex.getMessage());
        }

        long periodMs = Math.max(5_000L, setpointRefreshMs());
        ScheduledFuture<?> hold = taskScheduler.scheduleAtFixedRate(() -> {
            try {
                boolean keep = batteryManagementService.scheduleHoldTick();
                if (!keep) cancelHoldForSchedule(schedule.getId());
            } catch (Exception ex) {
                LogFilter.logWarn(ChargingManagementService.class, "HOLD tick failed: {}", ex.getMessage());
            }
        }, periodMs);
        holdTasks.put(schedule.getId(), hold);
    }

    private void scheduleChargingTask(ChargingSchedule schedule) {
        long id = schedule.getId();
        Date startTime = new Date(schedule.getStartTimestamp());
        ScheduledFuture<?> existingTask = scheduledTasks.get(id);

        if (existingTask == null || !chargingUtils.isTaskUpToDate(existingTask, schedule)) {
            if (existingTask != null) chargingUtils.cancelTask(id, scheduledTasks);
            ScheduledFuture<?> task = taskScheduler.schedule(() -> executeChargingTask(schedule), startTime);
            scheduledTasks.put(id, task);
            LogFilter.logInfo(ChargingManagementService.class, "Scheduled start task for: {}", DATE_FORMAT.format(startTime));
        }
    }

    private void scheduleStopTask(ChargingSchedule schedule) {
        long stopId = schedule.getEndTimestamp();
        Date stopTime = new Date(stopId);
        ScheduledFuture<?> existingTask = scheduledTasks.get(stopId);

        if (existingTask == null || !chargingUtils.isTaskUpToDate(existingTask, schedule)) {
            if (existingTask != null) chargingUtils.cancelTask(stopId, scheduledTasks);
            ScheduledFuture<?> task = taskScheduler.schedule(() -> {
                LogFilter.logInfo(ChargingManagementService.class, "Window end: {}", DATE_FORMAT.format(stopTime));
                cancelHoldForSchedule(schedule.getId());
                catchupAttempts.remove(schedule.getId());

                boolean atNight = ChargingUtils.isNight(stopTime.getTime());
                boolean manualIdle = batteryManagementService.isManualIdleActive();
                if ((chargingUtils.isNightChargingIdle() && atNight) || manualIdle) {
                    batteryManagementService.setChargeWatts(nightPauseWatts()); // e.g. hold ~1W
                    LogFilter.logInfo(ChargingManagementService.class, "Idle/hold active -> set {} W minimal setpoint.", nightPauseWatts());
                } else {
                    boolean ok = batteryManagementService.resetToAutomaticMode(true);
                    LogFilter.log(ChargingManagementService.class,
                            ok ? LogFilter.LOG_LEVEL_INFO : LogFilter.LOG_LEVEL_WARN,
                            ok ? "Returned to automatic mode at end of window."
                                    : "Failed to return to automatic mode at end of window.");
                }
            }, stopTime);
            scheduledTasks.put(stopId, task);
            LogFilter.logInfo(ChargingManagementService.class, "Scheduled stop task for: {}", DATE_FORMAT.format(stopTime));
        }
    }

    private void cancelHoldForSchedule(Long scheduleId) {
        ScheduledFuture<?> hold = holdTasks.remove(scheduleId);
        if (hold != null) {
            hold.cancel(false);
            LogFilter.logDebug(ChargingManagementService.class, "Cancelled HOLD for schedule {}", scheduleId);
        }
    }

    private void ensureManualHoldHeartbeat() {
        if (manualHoldHeartbeat != null && !manualHoldHeartbeat.isCancelled()) return;

        long periodMs = Math.max(5_000L, setpointRefreshMs());
        manualHoldHeartbeat = taskScheduler.scheduleAtFixedRate(() -> {
            try {
                boolean keep = batteryManagementService.manualHoldTick();
                if (!keep) {
                    batteryManagementService.setForcedChargingActive(false);
                    cancelManualHoldHeartbeat();
                }
            } catch (Exception ex) {
                LogFilter.logWarn(ChargingManagementService.class, "Manual hold heartbeat failed: {}", ex.getMessage());
            }
        }, periodMs);
    }

    private void cancelManualHoldHeartbeat() {
        if (manualHoldHeartbeat != null) {
            manualHoldHeartbeat.cancel(false);
            manualHoldHeartbeat = null;
        }
    }

    // -------- nightly cleanup

    public void scheduleEndOfNightResetTask() {
        // Only relevant when Night-Idle is explicitly active; otherwise stop and clear stale tasks.
        if (!chargingUtils.isNightChargingIdle()) {
            ScheduledFuture<?> old = scheduledTasks.remove(END_OF_NIGHT_RESET_ID);
            if (old != null) old.cancel(false);
            return;
        }

        ScheduledFuture<?> existingTask = scheduledTasks.get(END_OF_NIGHT_RESET_ID);
        if (existingTask != null) existingTask.cancel(false);

        LocalDateTime resetTime = LocalDateTime.now(ZoneId.systemDefault())
                .with(LocalTime.of(NightConfig.getNightEndHour(), 0))
                .plusDays(LocalDateTime.now().getHour() >= NightConfig.getNightEndHour() ? 1 : 0)
                .minusMinutes(15);
        Date resetDate = Date.from(resetTime.atZone(ZoneId.systemDefault()).toInstant());

        scheduledTasks.put(END_OF_NIGHT_RESET_ID, taskScheduler.schedule(() -> {
            batteryManagementService.setManualIdleActive(false);
            boolean success = batteryManagementService.resetToAutomaticMode();
            LogFilter.log(ChargingManagementService.class, success ? LogFilter.LOG_LEVEL_INFO : LogFilter.LOG_LEVEL_ERROR,
                    success ? "Successfully reset to automatic mode." : "Reset to automatic mode failed.");
        }, resetDate));
    }

    public void scheduleNightIdleWindowTasks() {
        scheduleNightIdleStartTask();
        scheduleEndOfNightResetTask();
    }

    public void activateNightIdleIfInWindow() {
        long now = System.currentTimeMillis();
        if (!ChargingUtils.isNight(now)) return;

        batteryManagementService.setManualIdleActive(true);
        if (batteryManagementService.pauseWithTinySetpoint()) {
            batteryManagementService.startManualIdleHold();
            LogFilter.logInfo(ChargingManagementService.class, "Night idle active in window -> set 1W and hold started.");
        } else {
            batteryManagementService.setManualIdleActive(false);
            LogFilter.logWarn(ChargingManagementService.class, "Night idle activation failed: 1W setpoint could not be applied.");
        }
    }

    private void scheduleNightIdleStartTask() {
        if (!chargingUtils.isNightChargingIdle()) {
            ScheduledFuture<?> old = scheduledTasks.remove(START_OF_NIGHT_IDLE_ID);
            if (old != null) old.cancel(false);
            return;
        }

        ScheduledFuture<?> existing = scheduledTasks.get(START_OF_NIGHT_IDLE_ID);
        if (existing != null) existing.cancel(false);

        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime nextStart = resolveNextNightStart(now);
        Date startDate = Date.from(nextStart.atZone(zone).toInstant());

        scheduledTasks.put(START_OF_NIGHT_IDLE_ID, taskScheduler.schedule(() -> {
            if (chargingUtils.isNightChargingIdle()) {
                activateNightIdleIfInWindow();
            }
            scheduleNightIdleStartTask();
        }, startDate));

        LogFilter.logInfo(ChargingManagementService.class, "Scheduled night-idle start task for: {}", DATE_FORMAT.format(startDate));
    }

    private LocalDateTime resolveNextNightStart(LocalDateTime reference) {
        int nightStartHour = NightConfig.getNightStartHour();
        int nightEndHour = NightConfig.getNightEndHour();

        LocalDateTime start = reference.with(LocalTime.of(nightStartHour, 0)).withSecond(0).withNano(0);
        if (nightStartHour == nightEndHour) {
            return start.isAfter(reference) ? start : start.plusDays(1);
        }
        return reference.isBefore(start) ? start : start.plusDays(1);
    }

    // -------- sync

    private void synchronizeSchedules(Set<ChargingSchedule> newSchedules) {
        long currentTime = System.currentTimeMillis();

        List<ChargingSchedule> existingSchedules = chargingScheduleRepository.findAll().stream()
                .filter(s -> s.getEndTimestamp() > currentTime)
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());

        Set<ChargingSchedule> validatedNew = chargingUtils.validateSchedulesForCheaperOptions(newSchedules);
        List<MarketPrice> marketPrices = marketPriceRepository.findAll();

        Set<String> newKeys = validatedNew.stream()
                .map(s -> s.getStartTimestamp() + "-" + s.getEndTimestamp())
                .collect(Collectors.toSet());

        existingSchedules.stream()
                .filter(s -> s.getStartTimestamp() > currentTime)
                .filter(s -> !newKeys.contains(s.getStartTimestamp() + "-" + s.getEndTimestamp()))
                .forEach(s -> {
                    cancelHoldForSchedule(s.getId());
                    chargingUtils.cancelTask(s.getId(), scheduledTasks);
                    catchupAttempts.remove(s.getId());
                    chargingScheduleRepository.delete(s);
                    LogFilter.logInfo(ChargingManagementService.class,
                            "Removed outdated schedule: {} - {} ({} ct/kWh)",
                            DATE_FORMAT.format(new Date(s.getStartTimestamp())),
                            DATE_FORMAT.format(new Date(s.getEndTimestamp())), s.getPrice());
                });

        removeOutdatedSchedules(existingSchedules, validatedNew);
        saveChargingSchedule(validatedNew, marketPrices);

        schedulePlannedCharging();
        scheduleStopPlannedCharging();
    }

    private void removeOutdatedSchedules(List<ChargingSchedule> existing, Set<ChargingSchedule> news) {
        existing.forEach(e -> {
            boolean cheaper = news.stream().anyMatch(n -> n.getStartTimestamp().equals(e.getStartTimestamp()) &&
                    n.getEndTimestamp().equals(e.getEndTimestamp()) && n.getPrice() < e.getPrice());
            if (cheaper) {
                cancelHoldForSchedule(e.getId());
                chargingUtils.cancelTask(e.getId(), scheduledTasks);
                catchupAttempts.remove(e.getId());
                chargingScheduleRepository.delete(e);
            }
        });
    }

    private void saveChargingSchedule(Set<ChargingSchedule> schedules, List<MarketPrice> marketPrices) {
        long currentTime = System.currentTimeMillis();
        Set<ChargingSchedule> validated = new HashSet<>();
        schedules.stream()
                .filter(s -> s.getStartTimestamp() > currentTime)
                .filter(s -> chargingUtils.isValidSchedule(s, currentTime, validated, marketPrices))
                .filter(s -> !chargingScheduleRepository.existsByStartEndAndPrice(s.getStartTimestamp(), s.getEndTimestamp(), s.getPrice()))
                .forEach(s -> {
                    try {
                        chargingScheduleRepository.save(s);
                        validated.add(s);
                        LogFilter.logInfo(ChargingManagementService.class, "Saved schedule: {} - {} ({} ct/kWh).",
                                DATE_FORMAT.format(new Date(s.getStartTimestamp())),
                                DATE_FORMAT.format(new Date(s.getEndTimestamp())), s.getPrice());
                    } catch (Exception e) {
                        LogFilter.logError(ChargingManagementService.class, "Save failed: {} - {} at {} ct/kWh. {}",
                                DATE_FORMAT.format(new Date(s.getStartTimestamp())),
                                DATE_FORMAT.format(new Date(s.getEndTimestamp())), s.getPrice(), e.getMessage());
                    }
                });
    }

    public List<ChargingSchedule> getSortedChargingSchedules() {
        return chargingScheduleRepository.findAll().stream()
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());
    }

    private long calculateLatestNightStart(long referenceTimestamp) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime ref = LocalDateTime.ofInstant(Instant.ofEpochMilli(referenceTimestamp), zone);
        int nightStartHour = NightConfig.getNightStartHour();
        int nightEndHour = NightConfig.getNightEndHour();

        LocalDateTime nightStart = ref.with(LocalTime.of(nightStartHour, 0));
        LocalDateTime nightEnd = ref.with(LocalTime.of(nightEndHour, 0));

        if (nightStartHour > nightEndHour) {
            if (ref.getHour() < nightEndHour) {
                nightStart = nightStart.minusDays(1);
            } else {
                nightEnd = nightEnd.plusDays(1);
            }
        } else if (nightStartHour == nightEndHour) {
            nightEnd = nightEnd.plusDays(1);
        } else if (!ref.isBefore(nightEnd)) {
            nightStart = nightStart.plusDays(1);
            nightEnd = nightEnd.plusDays(1);
        }

        long nightEndTs = nightEnd.atZone(zone).toInstant().toEpochMilli();
        return nightEndTs - Math.max(0, nightLatestStartOffsetMinutes) * 60_000L;
    }
}
