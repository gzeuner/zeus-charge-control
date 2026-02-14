package de.zeus.power.service;

import de.zeus.power.config.LogFilter;
import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryStatusResponse;
import de.zeus.power.repository.ChargingScheduleRepository;
import de.zeus.power.util.ChargingUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static de.zeus.power.util.NightConfig.getNightEndHour;
import static de.zeus.power.util.NightConfig.getNightStartHour;

/**
 * Copyright 2025 Guido Zeuner - https://tiny-tool.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 *
 * Encapsulates all interactions with the battery/EMS: ownership, setpoints, RSOC cache/history,
 * and grid-optimized target computation. Serves both planned and manual charging flows.
 */
@Service
public class BatteryManagementService {
    // === Owner/Hold mechanism to prevent unintended hand-back to EM ===
    private enum ControlOwner { EM, SCHEDULE, MANUAL }

    @Value("${battery.manual.hold.ms:900000}") // 15 min
    private long manualHoldMs;

    @Value("${battery.manual.idle.hold.ms:900000}") // 15 min (Manual-Idle)
    private long manualIdleHoldMs;


    @Value("${battery.schedule.hold.ms:600000}") // 10 min
    private long scheduleHoldMs;

    /** Default/forced charge power (used for manual/FE start). */
    @Value("${battery.inverter.max.watts:4600}")
    private int defaultChargeWatt;

    /** Grid import limit (added to PV surplus in grid-optimized formula). */
    @Value("${battery.grid.import.limit.watts:4600}")
    private int gridImportLimitWatt;

    /** Explicit inverter max power as upper bound (falls back to defaultChargeWatt if unset/non-positive). */
    @Value("${battery.inverter.max.watts:0}")
    private int inverterMaxWattConfigured;

    /** Minimal setpoint to "pause" while keeping EMS control (1 W proven valid). */
    @Value("${battery.night.pause.watts:1}")
    private int nightPauseWatts;

    private volatile ControlOwner controlOwner = ControlOwner.EM;
    private final java.util.concurrent.atomic.AtomicLong ownerValidUntil = new java.util.concurrent.atomic.AtomicLong(0L);

    private void acquireOwner(ControlOwner owner, long durationMs) {
        controlOwner = owner;
        long until = System.currentTimeMillis() + Math.max(0, durationMs);
        ownerValidUntil.set(until);
        LogFilter.logInfo(BatteryManagementService.class, "Owner acquired: {} until {}", owner, Instant.ofEpochMilli(until));
    }

    // Keep ownership until a fixed point in time (e.g., end of night)
    private void acquireOwnerUntil(ControlOwner owner, long untilEpochMs) {
        controlOwner = owner;
        ownerValidUntil.set(Math.max(System.currentTimeMillis(), untilEpochMs));
        LogFilter.logInfo(BatteryManagementService.class, "Owner acquired: {} until {}", owner, Instant.ofEpochMilli(ownerValidUntil.get()));
    }

    public boolean isOwnerProtected() {
        long now = System.currentTimeMillis();
        boolean prot = controlOwner != ControlOwner.EM && now < ownerValidUntil.get();
        if (prot) {
            LogFilter.logDebug(BatteryManagementService.class, "Owner protection active: {} until {}", controlOwner, Instant.ofEpochMilli(ownerValidUntil.get()));
        }
        return prot;
    }

    public String getOwnerInfo() {
        return controlOwner + " until " + Instant.ofEpochMilli(ownerValidUntil.get());
    }

    private void releaseOwnerToEm(String reason) {
        controlOwner = ControlOwner.EM;
        ownerValidUntil.set(0L);
        LogFilter.logInfo(BatteryManagementService.class, "Owner released to EM. Reason={}", reason);
    }

    // Retries/defaults
    private static final int RETRY_DELAY_MS = 15_000;
    private static final int MAX_RETRIES = 4;
    private static final int DEFAULT_RSOC = 15;

    // Deps
    private final BatteryCommandService commandService;
    private final ChargingScheduleRepository chargingScheduleRepository;
    private final boolean batteryNotConfigured;

    // Config
    @Value("${battery.target.stateOfCharge:90}")
    private int targetStateOfCharge;

    @Value("${battery.status.cache.duration.seconds:10}")
    private int cacheDurationInSeconds;

    @Value("${battery.history.max.entries:24}")
    private int maxHistorySize;

    @Value("${battery.nightChargingIdle:false}")
    private volatile boolean nightChargingIdle;

    // State
    private boolean manualIdleActive = false;
    private BatteryStatusResponse cachedBatteryStatus;
    private java.time.Instant cacheTimestamp;
    private volatile boolean forcedChargingActive = false;
    private volatile boolean isReducedChargingActive = false;
    private volatile boolean nightIdleActive = false;
    private int lastSetpointW;
    private final Queue<Map.Entry<Long, Integer>> rsocHistory = new ConcurrentLinkedQueue<>();

    public BatteryManagementService(BatteryCommandService commandService,
                                    ChargingScheduleRepository chargingScheduleRepository) {
        this.commandService = commandService;
        this.chargingScheduleRepository = chargingScheduleRepository;
        this.batteryNotConfigured = commandService.isBatteryNotConfigured();
    }

    // ------------ helpers

    public int getDefaultChargingPointWatts() {
        return Math.max(0, defaultChargeWatt);
    }

    private int getInverterMaxWatt() {
        int configured = inverterMaxWattConfigured > 0 ? inverterMaxWattConfigured : defaultChargeWatt;
        return Math.max(0, configured);
    }

    /** @deprecated Use setChargeWatts(...) or setDischargeWatts(...) */
    @Deprecated
    public boolean setDynamicChargingPoint(int watts) { return setChargeWatts(watts); }

    public boolean setChargeWatts(int watts) {
        if (isBatteryNotConfigured() || watts < 0) return false;
        int clamped = Math.min(Math.max(0, watts), getInverterMaxWatt());
        var resp = commandService.setChargePoint(clamped);
        if (resp.success()) {
            this.lastSetpointW = clamped;
            this.isReducedChargingActive = false;
            return true;
        }
        return false;
    }

    public boolean setDischargeWatts(int watts) {
        if (isBatteryNotConfigured() || watts < 0) return false;
        int clamped = Math.min(Math.max(0, watts), getInverterMaxWatt());
        var resp = commandService.setDischargePoint(clamped);
        if (resp.success()) {
            this.lastSetpointW = -clamped;
            this.isReducedChargingActive = false;
            return true;
        }
        return false;
    }

    public boolean pauseWithTinySetpoint() {
        return setChargeWatts(Math.max(1, nightPauseWatts));
    }

    /** Begin a manual idle hold so user action has priority for a while. */
    public void startManualIdleHold() {
        acquireOwner(ControlOwner.MANUAL, manualIdleHoldMs > 0 ? manualIdleHoldMs : manualHoldMs);
    }

    // ------------ RSOC history/cache

    public void updateRsocHistory(long currentTime, int currentRsoc) {
        if (currentRsoc < 0 || currentRsoc > 100) return;
        rsocHistory.add(new AbstractMap.SimpleEntry<>(currentTime, currentRsoc));
        while (rsocHistory.size() > maxHistorySize) rsocHistory.poll();
    }

    public synchronized List<Map.Entry<Long, Integer>> getRsocHistory() {
        if (rsocHistory.size() < 2) return Collections.emptyList();
        List<Map.Entry<Long, Integer>> history = new ArrayList<>(rsocHistory);
        long lastTs = 0;
        for (Map.Entry<Long, Integer> e : history) {
            if (e == null || e.getKey() == null || e.getValue() == null || e.getKey() < lastTs) {
                rsocHistory.clear();
                return Collections.emptyList();
            }
            lastTs = e.getKey();
        }
        return history;
    }

    // ------------ main ops

    public boolean initCharging(boolean forceCharging) {
        // User willfully starts charging: override any idle mode/state and give control to us
        setManualIdleActive(false);
        this.forcedChargingActive = forceCharging;
        invalidateBatteryCache();

        if (!checkPreconditions()) { resetForcedCharging(); return false; }

        long now = System.currentTimeMillis();
        Optional<ChargingSchedule> activeSchedule = findActiveSchedule(now);
        if (activeSchedule.isEmpty() && !forceCharging) {
            LogFilter.logInfo(BatteryManagementService.class, "No active charging schedule. Skip.");
            resetForcedCharging();
            return false;
        }

        int rsoc = getRelativeStateOfCharge();
        if (rsoc >= targetStateOfCharge) {
            LogFilter.logInfo(BatteryManagementService.class, "Skip start: RSOC {}% >= target {}%.", rsoc, targetStateOfCharge);
            resetForcedCharging();
            return false;
        }

        ApiResponse<?> modeResponse = activateManualOperatingMode();
        if (!modeResponse.success()) {
            LogFilter.logError(BatteryManagementService.class, "Manual mode activation failed: {}", modeResponse.message());
            resetForcedCharging();
            return false;
        }

        int targetWatts = forceCharging ? getDefaultChargingPointWatts()
                : computeGridOptimizedChargeTarget();
        if (!setChargeWatts(targetWatts)) {
            LogFilter.logError(BatteryManagementService.class, "Failed to set setpoint to {} W.", targetWatts);
            resetForcedCharging();
            return false;
        }

        // Acquire owner for hold (blocks /reset-automatic unless force=true)
        acquireOwner(forceCharging ? ControlOwner.MANUAL : ControlOwner.SCHEDULE,
                forceCharging ? manualHoldMs : scheduleHoldMs);

        isReducedChargingActive = false;
        LogFilter.logInfo(BatteryManagementService.class, "Charging initiated at {} W ({}).", targetWatts, forceCharging ? "Forced" : "Planned");
        return true;
    }

    /** Give back to EMS by neutralizing setpoint (0 W). */
    public boolean resetToAutomaticMode() {
        if (isBatteryNotConfigured()) return false;
        invalidateBatteryCache();
        ApiResponse<?> resp = commandService.setNeutralSetPoint();
        if (!resp.success()) {
            LogFilter.logInfo(BatteryManagementService.class, "Failed to return control to EM (0W): {}", resp.message());
            return false;
        }
        resetForcedCharging();
        isReducedChargingActive = false;
        lastSetpointW = 0;
        releaseOwnerToEm("resetToAutomaticMode");
        LogFilter.logInfo(BatteryManagementService.class, "Returned control to EMS (setpoint=0W).");
        return true;
    }

    /** Wrapper with owner protection (force=true overrides active hold) */
    public boolean resetToAutomaticMode(boolean force) {
        if (!force && isOwnerProtected()) {
            LogFilter.logInfo(BatteryManagementService.class,
                    "Skip return to EM due to owner protection ({}). Use force=true to override.", getOwnerInfo());
            return false;
        }
        return resetToAutomaticMode();
    }

    // ------------ status & cache

    public int getRelativeStateOfCharge() {
        if (isBatteryNotConfigured()) return 0;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                ApiResponse<BatteryStatusResponse> response = getBatteryStatusResponse();
                BatteryStatusResponse status = response != null ? response.data() : null;
                if (status != null) return status.getRsoc();
                logStatusFailure(attempt, response);
            } catch (Exception e) {
                LogFilter.logWarn(BatteryManagementService.class,
                        "Status read failed (attempt {}/{}). Cause: {}. Retrying...",
                        attempt, MAX_RETRIES, e.getMessage());
            }
            sleepWithRetry();
        }
        LogFilter.logError(BatteryManagementService.class, "Failed to retrieve RSOC after {} attempts. Using default {}%.", MAX_RETRIES, DEFAULT_RSOC);
        return DEFAULT_RSOC;
    }

    /** Manual mode activation is a no-op; we control via setpoints only. */
    public ApiResponse<?> activateManualOperatingMode() {
        invalidateBatteryCache();
        return new ApiResponse<>(true, org.springframework.http.HttpStatus.OK,
                "Manual mode switch skipped (setpoint control active).", null);
    }

    /** "Automatic mode" means handing back to EMS by setting 0 W. */
    public ApiResponse<?> activateAutomaticOperatingMode() {
        invalidateBatteryCache();
        ApiResponse<?> resp = commandService.setNeutralSetPoint();
        if (!resp.success()) return resp;
        resetForcedCharging();
        isReducedChargingActive = false;
        lastSetpointW = 0;
        releaseOwnerToEm("activateAutomaticOperatingMode");
        return new ApiResponse<>(true, org.springframework.http.HttpStatus.OK,
                "Returned control to Energy Manager (setpoint=0W).", null);
    }

    public BatteryStatusResponse getCurrentBatteryStatus() {
        if (isBatteryNotConfigured()) return null;
        if (isCacheValid()) return cachedBatteryStatus;

        ApiResponse<BatteryStatusResponse> response = getBatteryStatusResponse();
        return response != null ? response.data() : null;
    }

    private ApiResponse<BatteryStatusResponse> getBatteryStatusResponse() {
        if (isBatteryNotConfigured()) return null;
        if (isCacheValid()) {
            return new ApiResponse<>(true, org.springframework.http.HttpStatus.OK, "cached", cachedBatteryStatus);
        }
        ApiResponse<BatteryStatusResponse> response = commandService.getStatus();
        if (response != null && response.data() != null) {
            cachedBatteryStatus = response.data();
            cacheTimestamp = java.time.Instant.now();
        }
        return response;
    }

    private void logStatusFailure(int attempt, ApiResponse<BatteryStatusResponse> response) {
        if (response == null) {
            LogFilter.logWarn(BatteryManagementService.class,
                    "Status read failed (attempt {}/{}). Cause: no response. Retrying...",
                    attempt, MAX_RETRIES);
            return;
        }
        String status = response.statusCode() != null ? response.statusCode().toString() : "unknown";
        String message = response.message() != null ? response.message() : "n/a";
        LogFilter.logWarn(BatteryManagementService.class,
                "Status read failed (attempt {}/{}). Status: {}. Message: {}. Retrying...",
                attempt, MAX_RETRIES, status, message);
    }

    public boolean isBatteryNotConfigured() { return batteryNotConfigured; }
    public void invalidateBatteryCache() { cachedBatteryStatus = null; cacheTimestamp = null; }
    public boolean isManualIdleActive() { return manualIdleActive; }
    public void setManualIdleActive(boolean active) { this.manualIdleActive = active; }
    public boolean isForcedChargingActive() { return forcedChargingActive; }
    public void setForcedChargingActive(boolean v) { this.forcedChargingActive = v; }
    public int getLastSetpointW() { return lastSetpointW; }
    public void setLastSetpointW(int watts) { this.lastSetpointW = watts; }
    public boolean isNightChargingIdle() { return nightChargingIdle; }
    public void setNightChargingIdle(boolean nightChargingIdle) { this.nightChargingIdle = nightChargingIdle; }
    public boolean isNightIdleActive() { return nightIdleActive; }
    public void setNightIdleActive(boolean active) { this.nightIdleActive = active; }
    public boolean isScheduleOwnerActive() {
        return controlOwner == ControlOwner.SCHEDULE && System.currentTimeMillis() < ownerValidUntil.get();
    }

    public boolean activateNightIdleHold() {
        nightIdleActive = true;
        boolean ok = startNightIdleHold();
        if (!ok) nightIdleActive = false;
        return ok;
    }

    public boolean startNightIdleHold() {
        if (!ChargingUtils.isNight(System.currentTimeMillis())) return false;
        boolean okTiny = pauseWithTinySetpoint();
        if (!okTiny) return false;
        long nightEnd = getNightEndMillis(System.currentTimeMillis());
        acquireOwnerUntil(ControlOwner.MANUAL, nightEnd);
        return true;
    }

    private boolean isCacheValid() {
        return cachedBatteryStatus != null && cacheTimestamp != null &&
                ChronoUnit.SECONDS.between(cacheTimestamp, java.time.Instant.now()) <= cacheDurationInSeconds;
    }

    private void sleepWithRetry() {
        try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private boolean checkPreconditions() {
        boolean configured = !isBatteryNotConfigured();
        if (!configured) LogFilter.logInfo(BatteryManagementService.class, "Battery not configured. Skipping charging.");
        return configured;
    }

    private Optional<ChargingSchedule> findActiveSchedule(long currentTime) {
        return chargingScheduleRepository.findAll().stream()
                .filter(s -> currentTime >= s.getStartTimestamp() && currentTime < s.getEndTimestamp())
                .findFirst();
    }

    private void resetForcedCharging() { setForcedChargingActive(false); }

    // ------------ grid-optimized target

    /**
     * Pset = min(InverterMax, GridCap + max(0, Production - Consumption))
     */
    public int computeGridOptimizedChargeTarget() {
        try {
            BatteryStatusResponse s = getCurrentBatteryStatus();
            if (s == null) return Math.max(0, gridImportLimitWatt); // conservative fallback
            int production = Math.max(0, s.getProductionW());
            int consumption = Math.max(0, s.getConsumptionW());
            int pvSurplus = Math.max(0, production - consumption);
            int base = Math.max(0, gridImportLimitWatt) + pvSurplus;
            int target = Math.min(getInverterMaxWatt(), base);
            return Math.max(0, target);
        } catch (Exception ex) {
            return defaultChargeWatt;
        }
    }

    // ------------ HOLD ticks used by scheduler/UI

    /** FE/manual hold tick. Returns true to keep holding; false to end hold. */
    public boolean manualHoldTick() { return applyHoldTick(false); }

    /** Planned-window hold tick. Returns true to keep holding; false to end hold. */
    public boolean scheduleHoldTick() { return applyHoldTick(true); }

    private boolean applyHoldTick(boolean scheduledWindow) {
        if (isBatteryNotConfigured()) return false;

        long now = System.currentTimeMillis();
        int rsoc = getRelativeStateOfCharge();

        // If a scheduled window starts at night, temporarily disable night-idle so charging can proceed
        if (scheduledWindow && nightIdleActive && ChargingUtils.isNight(now) && nightChargingIdle) {
            nightIdleActive = false;
            LogFilter.logInfo(BatteryManagementService.class, "Scheduled window started @night -> night idle disabled for charging.");
        }

        // Manual idle hold: prioritize by setting ~1W and extending the lease
        if (manualIdleActive) {
            boolean okTiny = pauseWithTinySetpoint();
            if (!okTiny) {
                LogFilter.logWarn(BatteryManagementService.class,
                        "Manual Idle Hold active but failed to set tiny setpoint. Releasing to EM.");
                return false;
            }
            acquireOwner(ControlOwner.MANUAL, manualIdleHoldMs > 0 ? manualIdleHoldMs : manualHoldMs);
            LogFilter.logDebug(BatteryManagementService.class,
                    "Manual Idle Hold active -> keep {}W, lease extended to {}.",
                    Math.max(1, nightPauseWatts), Instant.ofEpochMilli(ownerValidUntil.get()));
            return true;
        }

        // RSOC cap
        if (rsoc >= targetStateOfCharge) {
            boolean night = ChargingUtils.isNight(now); // static util call
            if (night && this.nightChargingIdle) {
                // Night-idle exception: hold ~1W until night end (keep control)
                nightIdleActive = true;
                pauseWithTinySetpoint();
                long nightEnd = getNightEndMillis(now);
                acquireOwnerUntil(scheduledWindow ? ControlOwner.SCHEDULE : ControlOwner.MANUAL, nightEnd);
                LogFilter.logInfo(BatteryManagementService.class,
                        "RSOC {}% >= target {}% @night -> hold {}W until {}.",
                        rsoc, targetStateOfCharge, Math.max(1, nightPauseWatts), Instant.ofEpochMilli(nightEnd));
                return true; // keep holding until night end
            } else {
                // Default case: hand back to EM immediately, do not keep control
                boolean ok = resetToAutomaticMode(true);
                LogFilter.log(BatteryManagementService.class,
                        ok ? LogFilter.LOG_LEVEL_INFO : LogFilter.LOG_LEVEL_WARN,
                        "RSOC {}% >= target {}% -> hand back to EM (0W).", rsoc, targetStateOfCharge);
                return false; // end hold
            }
        }

        // Only keep owner/hold below target RSOC when we actually need to charge (targetW > 0)
        int targetW = scheduledWindow ? computeGridOptimizedChargeTarget() : getDefaultChargingPointWatts();

        if (targetW <= 0) {
            // No active charging need: do not take or keep control.
            LogFilter.logDebug(BatteryManagementService.class,
                    "Computed targetW <= 0 ({}W). Not acquiring owner; letting EM control.", targetW);
            return false; // end hold for this window
        }

        boolean ok = setChargeWatts(targetW);
        if (!ok) return false;

        // Only hold ownership while we are charging
        acquireOwner(scheduledWindow ? ControlOwner.SCHEDULE : ControlOwner.MANUAL,
                scheduledWindow ? scheduleHoldMs : manualHoldMs);

        LogFilter.logInfo(BatteryManagementService.class,
                "Charging active at {}W (owner {}).", targetW, scheduledWindow ? "SCHEDULE" : "MANUAL");
        return true;
    }

    public long getNightEndMillis(long referenceEpochMs) {
        int nightStartHour = getNightStartHour();
        int nightEndHour   = getNightEndHour();
        validateNightHours(nightStartHour, nightEndHour);

        java.time.ZonedDateTime ref =
                java.time.Instant.ofEpochMilli(referenceEpochMs).atZone(java.time.ZoneId.systemDefault());
        java.time.ZonedDateTime todayEnd =
                ref.withHour(nightEndHour).withMinute(0).withSecond(0).withNano(0);

        if (nightStartHour == nightEndHour) {
            return !ref.isAfter(todayEnd)
                    ? todayEnd.toInstant().toEpochMilli()
                    : todayEnd.plusDays(1).toInstant().toEpochMilli();
        }

        if (nightStartHour < nightEndHour) {
            return ref.isBefore(todayEnd)
                    ? todayEnd.toInstant().toEpochMilli()
                    : todayEnd.plusDays(1).toInstant().toEpochMilli();
        } else {
            return (ref.getHour() >= nightStartHour)
                    ? todayEnd.plusDays(1).toInstant().toEpochMilli()
                    : todayEnd.toInstant().toEpochMilli();
        }
    }

    private static void validateNightHours(int start, int end) {
        if (start < 0 || start > 23 || end < 0 || end > 23) {
            throw new IllegalArgumentException("Invalid night hours: " + start + " to " + end);
        }
    }

}
