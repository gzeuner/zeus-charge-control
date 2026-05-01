package de.zeus.power.controller;

import jakarta.servlet.http.HttpServletRequest;
import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryCapacitySnapshot;
import de.zeus.power.model.BatteryStatusMetrics;
import de.zeus.power.model.BatteryStatusResponse;
import de.zeus.power.model.PriceBreakdown;
import de.zeus.power.service.BatteryManagementService;
import de.zeus.power.service.BatteryCapacityService;
import de.zeus.power.service.BatteryStatusMetricsService;
import de.zeus.power.service.ChargingManagementService;
import de.zeus.power.service.MarketPriceService;
import de.zeus.power.service.PriceDisplayService;
import de.zeus.power.service.SeasonalTargetStateOfChargeService;
import de.zeus.power.util.ChargingUtils;
import de.zeus.power.util.NightConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
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
    @Autowired private PriceDisplayService priceDisplayService;
    @Autowired private SeasonalTargetStateOfChargeService seasonalTargetStateOfChargeService;
    @Autowired private BatteryStatusMetricsService batteryStatusMetricsService;
    @Autowired private BatteryCapacityService batteryCapacityService;

    @GetMapping("/charging-status")
    public String getChargingStatus(HttpServletRequest request, @RequestParam(name = "lang", required = false) String lang, Model model) {
        Locale locale = lang != null && !lang.isEmpty() ? Locale.forLanguageTag(lang) : request.getLocale();
        model.addAttribute("lang", locale.getLanguage());

        List<MarketPrice> marketPrices = marketPriceService.getAllMarketPrices()
                .stream()
                .sorted(Comparator.comparingLong(MarketPrice::getStartTimestamp))
                .toList();

        BatteryStatusResponse batteryStatus = batteryManagementService.getCurrentBatteryStatus();
        BatteryCapacitySnapshot batteryCapacity = batteryCapacityService.calculateSnapshot(batteryStatus);

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
            model.addAttribute("marketPricesJson", mapper.writeValueAsString(toDisplayMarketPriceList(marketPrices)));
            model.addAttribute("cheapestPeriodsJson", mapper.writeValueAsString(toDisplayMarketPriceList(cheapestPeriods)));
            model.addAttribute("scheduledChargingPeriodsJson", mapper.writeValueAsString(toDisplayChargingScheduleList(scheduledChargingPeriods)));
        } catch (JsonProcessingException e) {
            log.error("Fehler bei der JSON-Serialisierung", e);
            model.addAttribute("marketPricesJson", "[]");
            model.addAttribute("cheapestPeriodsJson", "[]");
            model.addAttribute("scheduledChargingPeriodsJson", "[]");
        }

        model.addAttribute("marketPrices", marketPrices);
        model.addAttribute("batteryStatus", batteryStatus);
        model.addAttribute("batteryCapacity", batteryCapacity);
        model.addAttribute("cheapestPeriods", cheapestPeriods);
        model.addAttribute("scheduledChargingPeriods", scheduledChargingPeriods);
        model.addAttribute("cheapestPrice", cheapestPrice);
        model.addAttribute("targetStateOfCharge", currentTargetStateOfCharge());
        model.addAttribute("activeSeasonLabel", resolveActiveSeasonLabel(locale));
        model.addAttribute("capacityTooltip", buildCapacityTooltip(batteryCapacity, locale));
        model.addAttribute("dropRateTooltip", messageSource.getMessage("dropRateTooltip", null, locale));
        model.addAttribute("estimatedTimeToTargetTooltip", messageSource.getMessage("estimatedTimeToTargetTooltip", null, locale));
        model.addAttribute("modeTooltip", messageSource.getMessage("modeTooltip", null, LocaleContextHolder.getLocale()));
        model.addAttribute("nightIdleTooltip", messageSource.getMessage("nightIdleTooltip", null, LocaleContextHolder.getLocale()));
        model.addAttribute("batteryNotConfigured", batteryManagementService.isBatteryNotConfigured());
        model.addAttribute("nightChargingIdle", chargingUtils.isNightChargingIdle());
        model.addAttribute("nightIdleActive", batteryManagementService.isNightIdleActive());

        BatteryStatusMetrics batteryStatusMetrics = batteryStatusMetricsService.calculateCurrentMetrics();
        Double dropRate = batteryStatusMetrics.dropRatePerHour();
        Double currentPrice = marketPriceService.getCurrentlyValidPrice();
        Double estimatedTimeToTarget = batteryStatusMetrics.estimatedTimeToTargetHours();
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
        BatteryStatusResponse batteryStatus = batteryManagementService.getCurrentBatteryStatus();
        BatteryCapacitySnapshot batteryCapacity = batteryCapacityService.calculateSnapshot(batteryStatus);

        status.put("currentMode", manualIdle ? "idle" : "standard"); // mirror mode for UI
        status.put("manualIdleActive", manualIdle);                   // explicit flag for UI polling
        status.put("nightChargingIdle", chargingUtils.isNightChargingIdle());
        status.put("nightIdleActive", batteryManagementService.isNightIdleActive());
        status.put("isCharging", batteryManagementService.isForcedChargingActive());
        status.put("currentPrice", marketPriceService.getCurrentlyValidPrice());
        BatteryStatusMetrics batteryStatusMetrics = batteryStatusMetricsService.calculateCurrentMetrics();
        status.put("dropRate", batteryStatusMetrics.dropRatePerHour());
        status.put("estimatedTimeToTarget", batteryStatusMetrics.estimatedTimeToTargetHours());
        status.put("stateOfCharge", batteryCapacity.stateOfChargePercent());
        status.put("remainingCapacityWh", batteryCapacity.remainingCapacityWh());
        status.put("lastSetpointW", batteryManagementService.getLastSetpointW());
        status.put("nightStartHour", NightConfig.getNightStartHour());
        status.put("nightEndHour", NightConfig.getNightEndHour());
        addDisplayPrices(status, marketPriceService.getCurrentlyValidPrice());
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

                    // User-selected idle must override any active charging state.
                    batteryManagementService.setForcedChargingActive(false);

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
                    // Frontend requests must always take priority over active holds.
                    boolean force = true;
                    boolean ok = batteryManagementService.resetToAutomaticMode(force);
                    if (ok) {
                        batteryManagementService.setManualIdleActive(false);
                        log.info("Switched to standard mode (EM control) force={}", force);
                        return new ApiResponse<>(true, HttpStatus.OK, "Switched to standard mode (EM control)", null);
                    } else {
                        log.warn("Switch to standard failed (EM hand-back failed). force={}", force);
                        return new ApiResponse<>(false, HttpStatus.BAD_GATEWAY, "Failed to return to EM", null);
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
                        batteryManagementService.setManualIdleActive(false);
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

    private int currentTargetStateOfCharge() {
        return seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge();
    }

    private String resolveActiveSeasonLabel(Locale locale) {
        return messageSource.getMessage(seasonalTargetStateOfChargeService.getCurrentSeasonMessageKey(), null, locale);
    }

    private String buildCapacityTooltip(BatteryCapacitySnapshot batteryCapacity, Locale locale) {
        return messageSource.getMessage(
                "capacityTooltip",
                new Object[]{batteryCapacity.installedModules(), batteryCapacity.totalCapacityWh()},
                locale
        );
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

    private List<Map<String, Object>> toDisplayMarketPriceList(List<MarketPrice> prices) {
        if (prices == null) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (MarketPrice price : prices) {
            result.add(toDisplayMarketPrice(price));
        }
        return result;
    }

    private Map<String, Object> toDisplayMarketPrice(MarketPrice price) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (price == null) return map;
        map.put("id", price.getId());
        map.put("startTimestamp", price.getStartTimestamp());
        map.put("endTimestamp", price.getEndTimestamp());
        map.put("marketPrice", price.getMarketPrice());
        map.put("unit", price.getUnit());

        addDisplayPrices(map, price.getMarketPrice());
        return map;
    }

    private List<Map<String, Object>> toDisplayChargingScheduleList(List<ChargingSchedule> schedules) {
        if (schedules == null) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChargingSchedule schedule : schedules) {
            result.add(toDisplayChargingSchedule(schedule));
        }
        return result;
    }

    private Map<String, Object> toDisplayChargingSchedule(ChargingSchedule schedule) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (schedule == null) return map;
        map.put("id", schedule.getId());
        map.put("startTimestamp", schedule.getStartTimestamp());
        map.put("endTimestamp", schedule.getEndTimestamp());
        map.put("price", schedule.getPrice());
        addDisplayPrices(map, schedule.getPrice());
        return map;
    }

    private void addDisplayPrices(Map<String, Object> target, Double boerseNettoCt) {
        target.put("displayPriceNettoCt", boerseNettoCt);
        if (boerseNettoCt == null) {
            target.put("displayPriceBruttoCt", null);
            return;
        }
        PriceBreakdown breakdown = priceDisplayService.calculate(BigDecimal.valueOf(boerseNettoCt));
        target.put("displayPriceBruttoCt", breakdown != null && breakdown.getTotalBruttoCt() != null
                ? breakdown.getTotalBruttoCt().doubleValue()
                : null);
    }
}
