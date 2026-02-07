package de.zeus.power.controller;

import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryStatusResponse;
import de.zeus.power.service.BatteryManagementService;
import de.zeus.power.service.ChargingManagementService;
import de.zeus.power.service.MarketPriceService;
import de.zeus.power.util.ChargingUtils;
import de.zeus.power.util.NightConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Copyright 2025 Guido Zeuner - https://tiny-tool.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 *
 * MVC controller for status view and user actions (start/stop charging, toggle modes).
 * Bridges UI requests to services and assembles view model data.
 */
@Controller
public class ChargingStatusController {
    private static final Logger log = LoggerFactory.getLogger(ChargingStatusController.class);

    @Autowired private MarketPriceService marketPriceService;
    @Autowired private BatteryManagementService batteryManagementService;
    @Autowired private ChargingManagementService chargingManagementService;
    @Autowired private ChargingUtils chargingUtils;
    @Autowired private MessageSource messageSource;

    @Value("${battery.target.stateOfCharge}")
    private int targetStateOfCharge;

    @Value("${battery.inverter.max.watts:4600}")
    private int chargingPointInWatt;

    @GetMapping("/charging-status")
    public String getChargingStatus(HttpServletRequest request, @RequestParam(name = "lang", required = false) String lang, Model model) {
        Locale locale = lang != null && !lang.isEmpty() ? Locale.forLanguageTag(lang) : request.getLocale();
        model.addAttribute("lang", locale.getLanguage());

        List<MarketPrice> marketPrices = marketPriceService.getAllMarketPrices()
                .stream()
                .sorted(Comparator.comparingLong(MarketPrice::getStartTimestamp))
                .toList();

        BatteryStatusResponse batteryStatus = batteryManagementService.getCurrentBatteryStatus();

        List<MarketPrice> cheapestPeriods = marketPrices.stream()
                .filter(price -> price.getStartTimestamp() > System.currentTimeMillis())
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh)
                        .thenComparingLong(MarketPrice::getStartTimestamp))
                .limit(5)
                .toList();

        MarketPrice cheapestPrice = marketPrices.stream()
                .min(Comparator.comparingDouble(MarketPrice::getMarketPrice))
                .orElse(null);

        List<ChargingSchedule> scheduledChargingPeriods = chargingManagementService.getSortedChargingSchedules();

        ObjectMapper mapper = new ObjectMapper();
        try {
            model.addAttribute("marketPricesJson", mapper.writeValueAsString(marketPrices));
            model.addAttribute("cheapestPeriodsJson", mapper.writeValueAsString(cheapestPeriods));
            model.addAttribute("scheduledChargingPeriodsJson", mapper.writeValueAsString(scheduledChargingPeriods));
        } catch (JsonProcessingException e) {
            log.error("Fehler bei der JSON-Serialisierung", e);
            model.addAttribute("marketPricesJson", "[]");
            model.addAttribute("cheapestPeriodsJson", "[]");
            model.addAttribute("scheduledChargingPeriodsJson", "[]");
        }

        model.addAttribute("marketPrices", marketPrices);
        model.addAttribute("batteryStatus", batteryStatus);
        model.addAttribute("cheapestPeriods", cheapestPeriods);
        model.addAttribute("scheduledChargingPeriods", scheduledChargingPeriods);
        model.addAttribute("cheapestPrice", cheapestPrice);
        model.addAttribute("targetStateOfCharge", targetStateOfCharge);
        model.addAttribute("modeTooltip", messageSource.getMessage("modeTooltip", null, LocaleContextHolder.getLocale()));
        model.addAttribute("nightIdleTooltip", messageSource.getMessage("nightIdleTooltip", null, LocaleContextHolder.getLocale()));
        model.addAttribute("batteryNotConfigured", batteryManagementService.isBatteryNotConfigured());
        model.addAttribute("nightChargingIdle", chargingUtils.isNightChargingIdle());
        model.addAttribute("nightIdleActive", batteryManagementService.isNightIdleActive());

        Double dropRate = calculateDropRate();
        Double currentPrice = marketPriceService.getCurrentlyValidPrice();
        Double estimatedTimeToTarget = calculateEstimatedTimeToTarget();
        model.addAttribute("dropRate", dropRate);
        model.addAttribute("currentPrice", currentPrice);
        model.addAttribute("estimatedTimeToTarget", estimatedTimeToTarget);
        model.addAttribute("currentTime", System.currentTimeMillis());
        model.addAttribute("gridChargingTooltip", buildGridChargingTooltip(currentPrice, dropRate, estimatedTimeToTarget, locale));
        model.addAttribute("currentMode", batteryManagementService.isManualIdleActive() ? "idle" : "standard");
        model.addAttribute("isCharging", batteryManagementService.isForcedChargingActive());
        model.addAttribute("lastSetpointW", batteryManagementService.getLastSetpointW());
        model.addAttribute("nightStartHour", NightConfig.getNightStartHour());
        model.addAttribute("nightEndHour", NightConfig.getNightEndHour());

        return "chargingStatusView";
    }

    @PostMapping("/start-charging")
    @ResponseBody
    public ApiResponse<Void> startCharging() {
        try {
            boolean ok = batteryManagementService.initCharging(true);
            if (ok) {
                log.info("Charging started");
                return new ApiResponse<>(true, HttpStatus.OK, "Charging started", null);
            } else {
                log.warn("Charging could not be started (preconditions not met or RSOC target reached)");
                return new ApiResponse<>(false, HttpStatus.BAD_REQUEST, "Charging not started (preconditions/RSOC)", null);
            }
        } catch (Exception e) {
            log.error("Error starting charging", e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to start charging: " + e.getMessage(), null);
        }
    }

    @PostMapping("/reset-automatic")
    @ResponseBody
    public ApiResponse<Void> resetToAutomaticMode(@RequestParam(name = "force", defaultValue = "false") boolean force) {
        try {
            batteryManagementService.setManualIdleActive(false); // UI state toggle
            boolean ok = batteryManagementService.resetToAutomaticMode(force); // Setpoint=0W + flags
            if (ok) {
                log.info("Handed back to Energy Manager (setpoint=0W)");
                return new ApiResponse<>(true, HttpStatus.OK, "Returned to EM (setpoint=0W)", null);
            } else {
                log.warn("Hand-back to Energy Manager failed");
                return new ApiResponse<>(false, HttpStatus.BAD_GATEWAY, "Failed to return to EM", null);
            }
        } catch (Exception e) {
            log.error("Error resetting to automatic mode", e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to reset to automatic mode: " + e.getMessage(), null);
        }
    }

    @PostMapping("/reset-idle")
    @ResponseBody
    public ApiResponse<Void> resetToIdleMode() {
        try {
            // Idle = manual control + 1 W setpoint + UI flag
            batteryManagementService.activateManualOperatingMode(); // No-op kept for backward compatibility
            boolean ok = batteryManagementService.pauseWithTinySetpoint(); // neutralizes setpoint
            batteryManagementService.setManualIdleActive(true);
            if (ok) {
                log.info("Reset to idle mode.");
                return new ApiResponse<>(true, HttpStatus.OK, "Reset to idle mode (0W)", null);
            } else {
                log.warn("Reset to idle failed (setpoint=1W could not be set)");
                return new ApiResponse<>(false, HttpStatus.BAD_GATEWAY, "Failed to set 0W in idle", null);
            }
        } catch (Exception e) {
            log.error("Error resetting to idle mode", e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to reset to idle mode: " + e.getMessage(), null);
        }
    }

    @PostMapping("/toggle-night-charging")
    @ResponseBody
    public ApiResponse<Void> toggleNightCharging(@RequestBody Map<String, Object> request) {
        try {
            boolean nightChargingIdle = Boolean.parseBoolean(String.valueOf(request.getOrDefault("nightChargingIdle", true)));
            Integer startHour = request.containsKey("startHour") ? parseHour(request.get("startHour")) : null;
            Integer endHour = request.containsKey("endHour") ? parseHour(request.get("endHour")) : null;

            if (startHour != null && endHour != null) {
                NightConfig.updateNightHours(startHour, endHour);
                log.info("Night hours updated by user: {} -> {} (during toggle request)", startHour, endHour);
            }

            chargingUtils.setNightChargingIdle(nightChargingIdle);
            batteryManagementService.setNightChargingIdle(nightChargingIdle);
            chargingManagementService.scheduleNightIdleWindowTasks();

            if (nightChargingIdle) {
                if (ChargingUtils.isNight(System.currentTimeMillis())) {
                    chargingManagementService.activateNightIdleIfInWindow();
                } else {
                    log.info("Night idle activated by user; waiting for configured night window.");
                }
            } else {
                // Disable idle and hand back control
                batteryManagementService.setNightIdleActive(false);
                boolean ok = batteryManagementService.resetToAutomaticMode(true);
                log.info("Night idle deactivated by user. Handback to EM success={}", ok);
            }

            return new ApiResponse<>(true, HttpStatus.OK, "Night charging behavior updated", null);
        } catch (Exception e) {
            log.error("Error toggling night charging", e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to toggle night charging: " + e.getMessage(), null);
        }
    }

    @PostMapping("/night-charging-window")
    @ResponseBody
    public ApiResponse<Map<String, Integer>> updateNightChargingWindow(@RequestBody Map<String, Object> request) {
        try {
            Integer startHour = parseHour(request.get("startHour"));
            Integer endHour = parseHour(request.get("endHour"));

            if (startHour == null || endHour == null) {
                log.warn("Invalid night window values received: startHour={}, endHour={}", request.get("startHour"), request.get("endHour"));
                return new ApiResponse<>(false, HttpStatus.BAD_REQUEST, "Start and end hours must be between 0 and 23", null);
            }

            NightConfig.updateNightHours(startHour, endHour);
            log.info("Night hours updated at runtime: {} -> {}", startHour, endHour);
            chargingManagementService.scheduleNightIdleWindowTasks();
            if (chargingUtils.isNightChargingIdle()) {
                chargingManagementService.activateNightIdleIfInWindow();
            }

            Map<String, Integer> data = new HashMap<>();
            data.put("startHour", startHour);
            data.put("endHour", endHour);
            return new ApiResponse<>(true, HttpStatus.OK, "Night charging window updated", data);
        } catch (Exception e) {
            log.error("Error updating night charging window", e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to update night charging window: " + e.getMessage(), null);
        }
    }

    private Integer parseHour(Object obj) {
        if (obj == null) return null;
        try {
            String s = obj.toString().trim();
            if (s.contains(":")) s = s.split(":")[0];
            int h = Integer.parseInt(s);
            return (h >= 0 && h <= 23) ? h : null;
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/current-status")
    @ResponseBody
    public Map<String, Object> getCurrentStatus() {
        Map<String, Object> status = new HashMap<>();
        boolean manualIdle = batteryManagementService.isManualIdleActive();

        status.put("currentMode", manualIdle ? "idle" : "standard"); // mirror mode for UI
        status.put("manualIdleActive", manualIdle);                   // explicit flag for UI polling
        status.put("nightChargingIdle", chargingUtils.isNightChargingIdle());
        status.put("nightIdleActive", batteryManagementService.isNightIdleActive());
        status.put("isCharging", batteryManagementService.isForcedChargingActive());
        status.put("currentPrice", marketPriceService.getCurrentlyValidPrice());
        status.put("dropRate", calculateDropRate());
        status.put("lastSetpointW", batteryManagementService.getLastSetpointW());
        status.put("nightStartHour", NightConfig.getNightStartHour());
        status.put("nightEndHour", NightConfig.getNightEndHour());
        return status;
    }

    @PostMapping("/toggle-mode")
    @ResponseBody
    public ApiResponse<Void> toggleMode(@RequestBody Map<String, String> request) {
        try {
            String mode = request.get("mode");
            if (mode == null || mode.trim().isEmpty()) {
                log.warn("Mode parameter is missing in request body");
                return new ApiResponse<>(false, HttpStatus.BAD_REQUEST, "Mode parameter is required", null);
            }
            mode = mode.trim().toLowerCase(Locale.ROOT);

            switch (mode) {
                case "idle": {
                    // Idempotence guard
                    if (batteryManagementService.isManualIdleActive()) {
                        log.info("Already in idle mode");
                        return new ApiResponse<>(true, HttpStatus.OK, "Already in idle mode", null);
                    }

                    // No-op retained for compatibility
                    batteryManagementService.activateManualOperatingMode();

                    boolean ok = batteryManagementService.pauseWithTinySetpoint();
                    if (ok) {
                        batteryManagementService.setManualIdleActive(true);
                        batteryManagementService.startManualIdleHold();
                        log.info("Switched to idle mode (pause ~1W, manual hold active).");
                        return new ApiResponse<>(true, HttpStatus.OK, "Switched to idle mode (pause ~1W, manual hold)", null);
                    } else {
                        log.warn("Switch to idle failed.");
                        return new ApiResponse<>(false, HttpStatus.BAD_GATEWAY, "Failed to set 0W", null);
                    }
                }
                case "standard": {
                    boolean force = Boolean.parseBoolean(String.valueOf(request.getOrDefault("force", "false")));
                    boolean ok = batteryManagementService.resetToAutomaticMode(force);
                    if (ok) {
                        batteryManagementService.setManualIdleActive(false);
                        log.info("Switched to standard mode (EM control) force={}", force);
                        return new ApiResponse<>(true, HttpStatus.OK, "Switched to standard mode (EM control)", null);
                    } else {
                        // If owner protection blocks and force=false, return 409 so UI can retry with force=true
                        log.warn("Switch to standard blocked (likely owner protection). force={}", force);
                        return new ApiResponse<>(false, HttpStatus.CONFLICT, "Blocked by active hold (confirm with force=true)", null);
                    }
                }
                default:
                    log.warn("Invalid mode value: {}", mode);
                    return new ApiResponse<>(false, HttpStatus.BAD_REQUEST, "Invalid mode value: " + mode, null);
            }
        } catch (Exception e) {
            log.error("Error toggling mode: {}", e.getMessage(), e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to toggle mode: " + e.getMessage(), null);
        }
    }


    @PostMapping("/toggle-charging")
    @ResponseBody
    public ApiResponse<Void> toggleCharging(@RequestBody Map<String, String> request) {
        try {
            String charging = request.get("charging");
            if (charging == null || charging.trim().isEmpty()) {
                log.warn("Charging parameter is missing in request body");
                return new ApiResponse<>(false, HttpStatus.BAD_REQUEST, "Charging parameter is required", null);
            }
            charging = charging.trim().toLowerCase(Locale.ROOT);

            switch (charging) {
                case "start": {
                    // Idempotence: already forced charging
                    if (batteryManagementService.isForcedChargingActive()) {
                        log.info("Charging already active (forced)");
                        return new ApiResponse<>(true, HttpStatus.OK, "Charging already active", null);
                    }

                    // Explicit user start should override idle/other modes (if RSOC < target)
                    batteryManagementService.setManualIdleActive(false);

                    boolean ok = batteryManagementService.initCharging(true);
                    if (ok) {
                        log.info("Charging started");
                        return new ApiResponse<>(true, HttpStatus.OK, "Charging started", null);
                    } else {
                        log.warn("Charging not started (preconditions/RSOC)");
                        return new ApiResponse<>(false, HttpStatus.BAD_REQUEST,
                                "Charging not started (preconditions/RSOC)", null);
                    }
                }

                case "stop": {
                    // Explicit user stop -> always force=true
                    boolean ok = batteryManagementService.resetToAutomaticMode(true);
                    if (ok) {
                        log.info("Charging stopped (handed back to EM, setpoint=1W)");
                        return new ApiResponse<>(true, HttpStatus.OK, "Charging stopped (EM control)", null);
                    } else {
                        log.warn("Stopping charge failed (EM hand-back failed)");
                        return new ApiResponse<>(false, HttpStatus.BAD_GATEWAY, "Failed to return to EM", null);
                    }
                }

                default:
                    log.warn("Invalid charging value: {}", charging);
                    return new ApiResponse<>(false, HttpStatus.BAD_REQUEST,
                            "Invalid charging value: " + charging, null);
            }
        } catch (Exception e) {
            log.error("Error toggling charging: {}", e.getMessage(), e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to toggle charging: " + e.getMessage(), null);
        }
    }

    private Double calculateDropRate() {
        List<Map.Entry<Long, Integer>> history = batteryManagementService.getRsocHistory();
        if (history.size() < 2) return null;
        Map.Entry<Long, Integer> oldest = history.get(0);
        Map.Entry<Long, Integer> latest = history.get(history.size() - 1);
        long timeDiffMinutes = (latest.getKey() - oldest.getKey()) / 60000;
        if (timeDiffMinutes <= 0) return null;
        return (double) (oldest.getValue() - latest.getValue()) / (timeDiffMinutes / 60.0);
    }

    private Double calculateEstimatedTimeToTarget() {
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        if (currentRsoc >= targetStateOfCharge) return null;
        double requiredCapacity = chargingUtils.calculateRequiredCapacity(currentRsoc);
        if (requiredCapacity <= 0 || chargingPointInWatt <= 0) return null;
        return requiredCapacity / chargingPointInWatt;
    }

    private String buildGridChargingTooltip(Double currentPrice, Double dropRate, Double estimatedTimeToTarget, Locale locale) {
        return String.format(
                "<strong>%s:</strong> %s<br><strong>%s:</strong> %s<br><strong>%s:</strong> %s",
                messageSource.getMessage("currentPriceLabel", null, locale),
                currentPrice != null ? String.format("%.2f cent/kWh", currentPrice) : "N/A",
                messageSource.getMessage("dropRateLabel", null, locale),
                dropRate != null ? String.format("%.2f %%/h", dropRate) : "N/A",
                messageSource.getMessage("estimatedTimeToTargetLabel", null, locale),
                estimatedTimeToTarget != null ? String.format("%.2f h", estimatedTimeToTarget) : "N/A"
        );
    }
}
