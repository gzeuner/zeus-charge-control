package de.zeus.power.service;

import de.zeus.power.config.BatteryProperties;
import de.zeus.power.config.LogFilter;
import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.event.MarketPricesUpdatedEvent;
import de.zeus.power.model.BatteryStatusResponse;
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
    @Autowired private SeasonalTargetStateOfChargeService seasonalTargetStateOfChargeService;

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

    @Autowired private GridChargeGuard gridGuard;
    @Autowired private BatteryCapacityService capacityService;
    @Value("${scheduling.preview.pv-kwp:0}")
    private double previewPvKwp;
    @Value("${scheduling.preview.household-watts:0}")
    private double previewLoad;
    @Value("${battery.rsoc.min.floor:15}")
    private int reserve;
    private final Map<Long, Long> pvWaitUntil = new ConcurrentHashMap<>();

    @Value("${scheduling.night.latestStart.offset.minutes:60}")
    private int nightLatestStartOffsetMinutes;

    @Value("${scheduling.daytime.lookahead.hours:12}")
    private int daytimeLookaheadHours;

    /** Score weight for delay in minutes when selecting additional periods (ct per minute). */
    @Value("${scheduling.addition.score.alpha.ct.per.min:0.02}")
    private double additionAlphaCtPerMin;

    /** If remaining required energy is small, prefer earlier slots within this price margin (ct/kWh). */
    @Value("${scheduling.addition.small.max.extra.ct:0.5}")
    private double additionSmallMaxExtraCt;

    /** Threshold (kWh) to treat remaining need as "small". */
    @Value("${scheduling.addition.small.need.kwh:0.5}")
    private double additionSmallNeedKwh;

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
    private final Map<Long, ScheduledFuture<?>> guardRetryTasks = new ConcurrentHashMap<>();
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
        batteryManagementService.retryNightHandback();
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

        if (!batteryManagementService.isNightIdleActive() && chargingUtils.isNightChargingIdle()
                && ChargingUtils.isNight(now) && !batteryManagementService.isManualIdleActive()
                && !batteryManagementService.isForcedChargingActive() && !batteryManagementService.isScheduleOwnerActive()
                && !batteryManagementService.isNightHandbackPending()) {
            batteryManagementService.activateNightIdleHold();
        }

        if (batteryManagementService.isNightIdleActive()) {
            boolean atNight = ChargingUtils.isNight(now);
            if (!atNight) {
                batteryManagementService.endNightIdleAndHandBack();
            } else if (!chargingUtils.isNightChargingIdle()) {
                batteryManagementService.setNightIdleActive(false);
            } else if (!batteryManagementService.isScheduleOwnerActive()
                    && !batteryManagementService.isForcedChargingActive()
                    && !batteryManagementService.isManualIdleActive()) {
                boolean okTiny = batteryManagementService.startNightIdleHold();
                if (okTiny) {
                    LogFilter.logDebug(ChargingManagementService.class,
                            "Night idle hold active (1W setpoint confirmed).");
                } else {
                    LogFilter.logWarn(ChargingManagementService.class,
                            "Night idle setpoint failed; retrying while the night window remains active.");
                }
            }
        }


        chargingUtils.handleAutomaticModeTransition(now);
        batteryManagementService.updateRsocHistory(now, rsoc);
        BatteryStatusResponse currentStatus = batteryManagementService.getCurrentBatteryStatus();
        batteryManagementService.updateBatteryStatusHistory(now, currentStatus);
        tryCatchupStart(now, rsoc);
        monitorGridCharge(System.currentTimeMillis(), currentStatus);
    }

    private synchronized void monitorGridCharge(long now, BatteryStatusResponse status) {
        if (!gridGuard.enabled()) return;
        if (status == null || status.getRsoc() <= reserve
                || batteryManagementService.isOwnerProtected() || batteryManagementService.isManualIdleActive()
                || batteryManagementService.isForcedChargingActive() || batteryManagementService.isNightIdleActive()) {
            gridGuard.reset();
            return;
        }
        var current = marketPriceRepository.findCurrentMarketPrice(now);
        var schedules = chargingScheduleRepository.findAll();
        boolean atTarget = status.getRsoc() >= currentTargetStateOfCharge();
        var target = current.flatMap(price -> cheaperWindow(chargingUtils.convertToChargingSchedule(price), schedules, now));
        if (!atTarget && target.isEmpty()) { gridGuard.reset(); return; }
        long priceBoundary = current.map(MarketPrice::getEndTimestamp).orElse(now + 60_000);
        long boundary = schedules.stream().mapToLong(ChargingSchedule::getStartTimestamp)
                .filter(t -> t > now).min().orElse(priceBoundary);
        boundary = Math.min(boundary, priceBoundary);
        long destination = target.map(ChargingSchedule::getStartTimestamp).orElse(boundary);
        gridGuard.tick(now, destination, boundary, status);
    }

    private Optional<ChargingSchedule> cheaperWindow(ChargingSchedule current, List<ChargingSchedule> plan, long now) {
        return chargingUtils.shouldWaitForCheaperWindow(current, plan, batteryManagementService)
                .filter(s -> !ChargingUtils.isNight(now) || s.getStartTimestamp() <= calculateLatestNightStart(now));
    }

    private boolean shouldDefer(ChargingSchedule current, long now) {
        var target = cheaperWindow(current, chargingScheduleRepository.findAll(), now);
        if (target.isPresent()) {
            LogFilter.logInfo(getClass(), "Window {} deferred for cheaper window {}.", current.getId(),
                    Instant.ofEpochMilli(target.get().getStartTimestamp()));
            return true;
        }
        long latest = current.getStartTimestamp() + Math.max(0, weatherWaitMaxMinutes) * 60_000L;
        if (!pvOnlyEnabled || now >= latest || ChargingUtils.isNight(now) || !weatherForecastService.isEnabled()
                || batteryManagementService.getRelativeStateOfCharge() < weatherWaitMinRsoc) return false;
        Long previous = pvWaitUntil.get(current.getId());
        if (previous != null) return now < previous;
        var sunny = weatherForecastService.findSunnySlot(now, latest);
        sunny.ifPresent(t -> {
            pvWaitUntil.put(current.getId(), t);
            LogFilter.logInfo(getClass(), "Window {} deferred for PV until {}.", current.getId(), Instant.ofEpochMilli(t));
        });
        return sunny.isPresent();
    }

    /** Allow mid-window start when RSOC falls below a hysteresis threshold while price window is still active. */
    private synchronized void tryCatchupStart(long now, int rsoc) {
        if (!catchupEnabled) return;
        if (batteryManagementService.isManualIdleActive() || batteryManagementService.isForcedChargingActive()) return;
        if (batteryManagementService.isOwnerProtected() && !batteryManagementService.isNightIdleActive()) return;

        int threshold = Math.max(0, currentTargetStateOfCharge() - Math.max(0, catchupRsocDropPct));
        if (rsoc >= threshold) return;

        Optional<ChargingSchedule> active = chargingScheduleRepository.findAll().stream()
                .filter(s -> now >= s.getStartTimestamp() && now < s.getEndTimestamp())
                .findFirst();
        if (active.isEmpty()) return;

        ChargingSchedule schedule = active.get();
        long catchupHorizon = schedule.getStartTimestamp() + Math.max(1, catchupMaxMinutesFromStart) * 60_000L;
        if (now > catchupHorizon) return; // outside allowed catch-up window
        if (catchupAttempts.contains(schedule.getId())) return; // already tried for this window

        if (shouldDefer(schedule, now)) return;
        if (startScheduledHold(schedule)) {
            catchupAttempts.add(schedule.getId());
            LogFilter.logInfo(getClass(), "Catch-up started inside window {} (RSOC {}%).", schedule.getId(), rsoc);
        }
    }

    // -------- optimize flow

    private void optimizeChargingSchedule() {
        Set<ChargingSchedule> optimized = new HashSet<>();
        optimized.addAll(optimizeNighttimeCharging());
        optimized.addAll(optimizeDaytimeCharging());

        int dynamicThreshold = chargingUtils.calculateDynamicDaytimeThreshold();
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        long currentTime = System.currentTimeMillis();
        int targetRsoc = currentTargetStateOfCharge();

        if (!ChargingUtils.isNight(currentTime) && currentRsoc <= dynamicThreshold) {
            if (currentRsoc < targetRsoc) {
                optimized.addAll(getFutureDaytimeSchedules(currentTime));
            }
        }
        synchronizeSchedules(optimized);
    }

    private synchronized void optimizeChargingProcess() {
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        List<MarketPrice> marketPrices = marketPriceRepository.findAll();
        int targetRsoc = currentTargetStateOfCharge();
        if (previewPvKwp > 0 && previewLoad > 0) {
            weatherForecastService.previewGridTarget(System.currentTimeMillis(), targetRsoc, reserve,
                    capacityService.resolveTotalCapacityWh(), previewPvKwp, previewLoad)
                    .ifPresent(value -> LogFilter.logInfo(getClass(),
                            "Grid target preview {}%, active seasonal target {}%.", value, targetRsoc));
        }

        if (currentRsoc >= targetRsoc) {
            removeAllPlannedChargingPeriods();
            return;
        }

        bufferDaytimeCharging(marketPrices);
        optimizeChargingSchedule();
        adjustChargingPeriodsDynamically(currentRsoc, marketPrices);
        schedulePlannedCharging();
        scheduleStopPlannedCharging();
        scheduleEndOfNightResetTask();
    }

    // -------- buffering / thresholds

    private void bufferDaytimeCharging(List<MarketPrice> marketPrices) {
        long currentTime = System.currentTimeMillis();
        List<MarketPrice> daytimePeriods = getDaytimePeriods(currentTime);
        daytimeBuffer.clear();
        if (daytimePeriods.isEmpty()) return;

        int dynamicMaxPeriods = chargingUtils.calculateDynamicMaxChargingPeriods(daytimePeriods.size(),
                chargingUtils.calculatePriceRange(daytimePeriods), batteryManagementService.getRelativeStateOfCharge());

        Set<ChargingSchedule> validated = daytimePeriods.stream()
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(dynamicMaxPeriods)
                .map(chargingUtils::convertToChargingSchedule)
                .filter(s -> chargingUtils.isValidSchedule(s, currentTime, new HashSet<>(daytimeBuffer), daytimePeriods))
                .collect(Collectors.toSet());

        daytimeBuffer.addAll(validated);
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
        return !hasFuture || currentRsoc < currentTargetStateOfCharge();
    }

    private void removeAllPlannedChargingPeriods() {
        List<ChargingSchedule> toRemove = chargingScheduleRepository.findAll().stream()
                .filter(s -> s.getEndTimestamp() > System.currentTimeMillis())
                .collect(Collectors.toList());
        if (toRemove.isEmpty()) return;

        int rsoc = batteryManagementService.getRelativeStateOfCharge();
        int targetRsoc = currentTargetStateOfCharge();
        LogFilter.logInfo(ChargingManagementService.class,
                "Removing all planned charging periods (count={}, RSOC={}%, target={}%).",
                toRemove.size(), rsoc, targetRsoc);

        toRemove.forEach(s -> {
            cancelHoldForSchedule(s.getId());
            chargingUtils.cancelTask(s.getId(), scheduledTasks);
            chargingUtils.cancelTask(s.getEndTimestamp(), scheduledTasks);
            pvWaitUntil.remove(s.getId());
            catchupAttempts.remove(s.getId());
            chargingScheduleRepository.delete(s);
            LogFilter.logInfo(ChargingManagementService.class,
                    "Removed schedule: {} - {} ({} ct/kWh).",
                    DATE_FORMAT.format(new Date(s.getStartTimestamp())),
                    DATE_FORMAT.format(new Date(s.getEndTimestamp())), s.getPrice());
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
                .filter(s -> s.getEndTimestamp() > System.currentTimeMillis())
                .sorted(Comparator.comparingLong(ChargingSchedule::getEndTimestamp))
                .forEach(this::scheduleStopTask);
    }

    private List<ChargingSchedule> optimizeDaytimeCharging() {
        List<MarketPrice> day = chargingUtils.filterFuturePeriods(marketPriceRepository.findAll()).stream()
                .filter(p -> !ChargingUtils.isNight(p.getStartTimestamp()))
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .collect(Collectors.toList());
        if (day.isEmpty()) return Collections.emptyList();

        int dynamicMaxPeriods = chargingUtils.calculateDynamicMaxChargingPeriods(day.size(),
                chargingUtils.calculatePriceRange(day), batteryManagementService.getRelativeStateOfCharge());

        long now = System.currentTimeMillis();
        long toleranceWindow = now + (daytimeLookaheadHours * 60L * 60L * 1000L);

        return day.stream()
                .filter(p -> p.getStartTimestamp() <= toleranceWindow)
                .limit(dynamicMaxPeriods)
                .map(chargingUtils::convertToChargingSchedule)
                .collect(Collectors.toList());
    }

    private List<ChargingSchedule> optimizeNighttimeCharging() {
        int rsoc = batteryManagementService.getRelativeStateOfCharge();
        int targetRsoc = currentTargetStateOfCharge();
        if (rsoc >= targetRsoc) return Collections.emptyList();

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

        Comparator<MarketPrice> comparator = Comparator
                .comparingDouble(MarketPrice::getPriceInCentPerKWh)
                .thenComparingLong(MarketPrice::getStartTimestamp);

        List<MarketPrice> preferred = night.stream()
                .filter(p -> p.getStartTimestamp() <= latestAcceptableStart)
                .sorted(comparator)
                .limit(maxNighttimePeriods)
                .collect(Collectors.toList());

        if (preferred.isEmpty()) { // fallback: allow late starts if nothing else fits
            preferred = night.stream()
                    .filter(p -> p.getStartTimestamp() < nightEndTimestamp)
                    .sorted(comparator)
                    .limit(maxNighttimePeriods)
                    .collect(Collectors.toList());
        }

        double needed = chargingUtils.calculateRequiredCapacity(rsoc);
        var selected = new ArrayList<MarketPrice>();
        for (var price : preferred) {
            if (needed <= 0) break;
            selected.add(price);
            needed -= chargingUtils.deliverableWh(Math.max(currentTime, price.getStartTimestamp()), price.getEndTimestamp());
        }
        preferred = selected;

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

    private void adjustChargingPeriodsDynamically(int currentRsoc, List<MarketPrice> marketPrices) {
        long now = System.currentTimeMillis();
        double required = chargingUtils.calculateRequiredCapacity(currentRsoc);
        if (required <= 0) { removeAllPlannedChargingPeriods(); return; }
        var existing = chargingUtils.getFutureChargingSchedules(chargingScheduleRepository.findAll(), now);
        double planned = existing.stream().mapToDouble(s -> chargingUtils.deliverableWh(
                Math.max(now, s.getStartTimestamp()), s.getEndTimestamp())).sum();
        // Keep the cheapest combination already planned; never remove an active window.
        var expensiveFirst = existing.stream().filter(s -> s.getStartTimestamp() > now)
                .sorted(Comparator.comparingDouble(ChargingSchedule::getPrice).reversed()
                        .thenComparing(ChargingSchedule::getStartTimestamp, Comparator.reverseOrder())).toList();
        for (var window : expensiveFirst) {
            double wh = chargingUtils.deliverableWh(window.getStartTimestamp(), window.getEndTimestamp());
            if (planned - wh < required) continue;
            chargingUtils.removeExcessChargingPeriods(List.of(window), 1, chargingScheduleRepository, scheduledTasks);
            planned -= wh;
        }
        if (planned < required) addAdditionalChargingPeriods(marketPrices, existing, required - planned);
    }

    private void addAdditionalChargingPeriods(List<MarketPrice> marketPrices,
                                              List<ChargingSchedule> existingSchedules,
                                              double requiredCapacityWh) {
        long currentTime = System.currentTimeMillis();
        long toleranceWindow = currentTime + (daytimeLookaheadHours * 60L * 60L * 1000L);

        List<MarketPrice> candidates = marketPrices.stream()
                .filter(p -> p.getStartTimestamp() > currentTime && p.getStartTimestamp() <= toleranceWindow)
                .filter(p -> existingSchedules.stream().noneMatch(s -> Objects.equals(s.getStartTimestamp(), p.getStartTimestamp())))
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .collect(Collectors.toList());

        List<MarketPrice> available = new ArrayList<>();
        if (!candidates.isEmpty()) {
            double bestPrice = candidates.get(0).getPriceInCentPerKWh();
            boolean smallNeed = (requiredCapacityWh / 1000.0) <= additionSmallNeedKwh;

            if (smallNeed) {
                // Prefer earliest slot within a small price premium
                available = candidates.stream()
                        .sorted(Comparator.<MarketPrice, Boolean>comparing(p -> p.getPriceInCentPerKWh() > bestPrice + additionSmallMaxExtraCt)
                                .thenComparingLong(MarketPrice::getStartTimestamp))
                        .collect(Collectors.toList());
            } else {
                // Score by price + alpha * delay minutes
                Comparator<MarketPrice> scoreComparator = Comparator
                        .<MarketPrice>comparingDouble(p -> p.getPriceInCentPerKWh()
                                + additionAlphaCtPerMin * Math.max(0.0, (p.getStartTimestamp() - currentTime) / 60000.0))
                        .thenComparingDouble(MarketPrice::getPriceInCentPerKWh)
                        .thenComparingLong(MarketPrice::getStartTimestamp);
                available = candidates.stream()
                        .sorted(scoreComparator)
                        .collect(Collectors.toList());
            }
        }

        List<ChargingSchedule> occupied = new ArrayList<>(existingSchedules);
        for (var price : available) {
            if (requiredCapacityWh <= 0) break;
            long start = price.getStartTimestamp(), end = price.getEndTimestamp();
            if (end <= start || occupied.stream().anyMatch(s -> start < s.getEndTimestamp() && end > s.getStartTimestamp())) continue;
            chargingUtils.createAndLogChargingSchedule(price, chargingScheduleRepository, DATE_FORMAT);
            occupied.add(chargingUtils.convertToChargingSchedule(price));
            requiredCapacityWh -= chargingUtils.deliverableWh(start, end);
        }
    }

    // -------- run/start/stop tasks (HOLD via scheduleHoldTick)

    private synchronized void executeChargingTask(ChargingSchedule schedule) {
        Date startTime = new Date(schedule.getStartTimestamp());
        Date endTime = new Date(schedule.getEndTimestamp());
        LogFilter.logInfo(ChargingManagementService.class, "Window start: {} - {}", DATE_FORMAT.format(startTime), DATE_FORMAT.format(endTime));

        long now = System.currentTimeMillis();
        if (batteryManagementService.isForcedChargingActive() || now < schedule.getStartTimestamp()
                || now >= schedule.getEndTimestamp() || shouldDefer(schedule, now)) return;
        startScheduledHold(schedule);
    }

    private boolean startScheduledHold(ChargingSchedule schedule) {
        if (holdTasks.containsKey(schedule.getId())) return true;
        long leaseUntil = gridGuard == null ? 0 : gridGuard.activeLeaseUntil(System.currentTimeMillis());
        if (leaseUntil > 0) {
            if (leaseUntil < schedule.getEndTimestamp()) {
                guardRetryTasks.computeIfAbsent(schedule.getId(), id -> taskScheduler.schedule(
                        () -> retryAfterGuard(id), new Date(leaseUntil + 100)));
                LogFilter.logInfo(getClass(), "Charging window {} will start after the temporary grid guard limit expires.", schedule.getId());
            }
            return false;
        }

        try {
            boolean keep = batteryManagementService.scheduleHoldTick();
            if (!keep) return false;
        } catch (Exception ex) {
            LogFilter.logWarn(ChargingManagementService.class, "Initial HOLD tick failed: {}", ex.getMessage());
            return false;
        }

        long periodMs = Math.max(5_000L, setpointRefreshMs());
        ScheduledFuture<?> hold = taskScheduler.scheduleAtFixedRate(() -> {
            try {
                boolean keep = System.currentTimeMillis() < schedule.getEndTimestamp()
                        && !batteryManagementService.isForcedChargingActive() && batteryManagementService.scheduleHoldTick();
                if (!keep) cancelHoldForSchedule(schedule.getId());
            } catch (Exception ex) {
                LogFilter.logWarn(ChargingManagementService.class, "HOLD tick failed: {}", ex.getMessage());
            }
        }, periodMs);
        holdTasks.put(schedule.getId(), hold);
        return true;
    }

    private synchronized void retryAfterGuard(long scheduleId) {
        guardRetryTasks.remove(scheduleId);
        chargingScheduleRepository.findById(scheduleId).ifPresent(this::executeChargingTask);
    }

    private void scheduleChargingTask(ChargingSchedule schedule) {
        long id = schedule.getId();
        Date startTime = new Date(schedule.getStartTimestamp());
        ScheduledFuture<?> existingTask = scheduledTasks.get(id);

        if (existingTask == null || existingTask.isCancelled()) {
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
            ScheduledFuture<?> task = taskScheduler.schedule(() -> stopWindow(schedule, stopTime), stopTime);
            scheduledTasks.put(stopId, task);
        }
    }

    private synchronized void stopWindow(ChargingSchedule schedule, Date stopTime) {
        LogFilter.logInfo(ChargingManagementService.class, "Window end: {}", DATE_FORMAT.format(stopTime));
        cancelHoldForSchedule(schedule.getId());
        ScheduledFuture<?> guardRetry = guardRetryTasks.remove(schedule.getId());
        if (guardRetry != null) guardRetry.cancel(false);
        catchupAttempts.remove(schedule.getId());
        pvWaitUntil.remove(schedule.getId());

        if (batteryManagementService.isForcedChargingActive() || !holdTasks.isEmpty()) return;
        batteryManagementService.releaseScheduledOwner();
        boolean atNight = ChargingUtils.isNight(stopTime.getTime());
        boolean manualIdle = batteryManagementService.isManualIdleActive();
        if (manualIdle) {
            batteryManagementService.pauseWithTinySetpoint();
            batteryManagementService.startManualIdleHold();
            LogFilter.logInfo(ChargingManagementService.class, "Manual idle active -> set {} W minimal setpoint.", nightPauseWatts());
        } else if (chargingUtils.isNightChargingIdle() && atNight) {
            boolean ok = batteryManagementService.activateNightIdleHold();
            if (ok) {
                LogFilter.logInfo(ChargingManagementService.class, "Night idle active -> set {} W minimal setpoint.", nightPauseWatts());
                long nightEndMs = batteryManagementService.getNightEndMillis(System.currentTimeMillis());
                LogFilter.logInfo(ChargingManagementService.class,
                        "Night-idle reactivated until night end: {}.",
                        DATE_FORMAT.format(new Date(nightEndMs)));
            } else {
                LogFilter.logWarn(ChargingManagementService.class, "Night idle activation failed; retrying during the night window.");
            }
        } else {
            boolean ok = batteryManagementService.resetToAutomaticMode(true);
            LogFilter.log(ChargingManagementService.class,
                    ok ? LogFilter.LOG_LEVEL_INFO : LogFilter.LOG_LEVEL_WARN,
                    ok ? "Returned to automatic mode at end of window."
                            : "Failed to return to automatic mode at end of window.");
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
                .plusDays(LocalDateTime.now().getHour() >= NightConfig.getNightEndHour() ? 1 : 0);
        Date resetDate = Date.from(resetTime.atZone(ZoneId.systemDefault()).toInstant());

        scheduledTasks.put(END_OF_NIGHT_RESET_ID, taskScheduler.schedule(() -> {
            boolean success = batteryManagementService.endNightIdleAndHandBack();
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
        if (batteryManagementService.isManualIdleActive() || batteryManagementService.isForcedChargingActive()
                || batteryManagementService.isScheduleOwnerActive() || !holdTasks.isEmpty()) return;

        if (batteryManagementService.activateNightIdleHold()) {
            LogFilter.logInfo(ChargingManagementService.class, "Night idle active in window -> set 1W and hold started.");
        } else {
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
                    chargingUtils.cancelTask(s.getEndTimestamp(), scheduledTasks);
                    pvWaitUntil.remove(s.getId());
                    catchupAttempts.remove(s.getId());
                    chargingScheduleRepository.delete(s);
                    LogFilter.logInfo(ChargingManagementService.class,
                            "Removed outdated schedule: {} - {} ({} ct/kWh)",
                            DATE_FORMAT.format(new Date(s.getStartTimestamp())),
                            DATE_FORMAT.format(new Date(s.getEndTimestamp())), s.getPrice());
                });

        removeOutdatedSchedules(existingSchedules, validatedNew);
        saveChargingSchedule(validatedNew, marketPrices);
    }

    private void removeOutdatedSchedules(List<ChargingSchedule> existing, Set<ChargingSchedule> news) {
        existing.forEach(e -> {
            boolean cheaper = news.stream().anyMatch(n -> n.getStartTimestamp().equals(e.getStartTimestamp()) &&
                    n.getEndTimestamp().equals(e.getEndTimestamp()) && n.getPrice() < e.getPrice());
            if (cheaper) {
                cancelHoldForSchedule(e.getId());
                chargingUtils.cancelTask(e.getId(), scheduledTasks);
                chargingUtils.cancelTask(e.getEndTimestamp(), scheduledTasks);
                catchupAttempts.remove(e.getId());
                chargingScheduleRepository.delete(e);
                LogFilter.logInfo(ChargingManagementService.class,
                        "Removed outdated schedule (cheaper duplicate exists): {} - {} ({} ct/kWh).",
                        DATE_FORMAT.format(new Date(e.getStartTimestamp())),
                        DATE_FORMAT.format(new Date(e.getEndTimestamp())), e.getPrice());
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

    private int currentTargetStateOfCharge() {
        return seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge();
    }
}
