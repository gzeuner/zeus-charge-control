package de.zeus.power.controller;

import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryStatusResponse;
import de.zeus.power.service.BatteryManagementService;
import de.zeus.power.service.ChargingManagementService;
import de.zeus.power.service.MarketPriceService;
import de.zeus.power.util.ChargingUtils;
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
 * Copyright 2024 Guido Zeuner - https://tiny-tool.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


/**
 * Controller responsible for managing the charging status view and related operations.
 */
@Controller
public class ChargingStatusController {

    private static final Logger log = LoggerFactory.getLogger(ChargingStatusController.class);

    @Autowired
    private MarketPriceService marketPriceService;

    @Autowired
    private BatteryManagementService batteryManagementService;

    @Autowired
    private ChargingManagementService chargingManagementService;

    @Autowired
    private ChargingUtils chargingUtils;

    @Autowired
    private MessageSource messageSource;

    @Value("${battery.target.stateOfCharge}")
    private int targetStateOfCharge;

    @Value("${battery.chargingPoint}")
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

        return "chargingStatusView";
    }

    @PostMapping("/start-charging")
    @ResponseBody
    public ApiResponse<Void> startCharging() {
        try {
            batteryManagementService.initCharging(true);
            log.info("Charging started");
            return new ApiResponse<>(true, HttpStatus.OK, "Charging started", null);
        } catch (Exception e) {
            log.error("Error starting charging", e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to start charging: " + e.getMessage(), null);
        }
    }

    @PostMapping("/reset-automatic")
    @ResponseBody
    public ApiResponse<Void> resetToAutomaticMode() {
        try {
            batteryManagementService.setManualIdleActive(false);
            batteryManagementService.resetToAutomaticMode();
            log.info("Reset to automatic mode");
            return new ApiResponse<>(true, HttpStatus.OK, "Reset to automatic mode", null);
        } catch (Exception e) {
            log.error("Error resetting to automatic mode", e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to reset to automatic mode: " + e.getMessage(), null);
        }
    }

    @PostMapping("/reset-idle")
    @ResponseBody
    public ApiResponse<Void> resetToIdleMode() {
        try {
            batteryManagementService.activateManualOperatingMode();
            batteryManagementService.setDynamicChargingPoint(0);
            batteryManagementService.setManualIdleActive(true);
            log.info("Reset to idle mode");
            return new ApiResponse<>(true, HttpStatus.OK, "Reset to idle mode", null);
        } catch (Exception e) {
            log.error("Error resetting to idle mode", e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to reset to idle mode: " + e.getMessage(), null);
        }
    }

    @PostMapping("/toggle-night-charging")
    @ResponseBody
    public ApiResponse<Void> toggleNightCharging(@RequestBody Map<String, Boolean> request) {
        try {
            boolean nightChargingIdle = request.getOrDefault("nightChargingIdle", true);
            chargingUtils.setNightChargingIdle(nightChargingIdle);
            log.info("Night charging behavior updated to: {}", nightChargingIdle);
            return new ApiResponse<>(true, HttpStatus.OK, "Night charging behavior updated", null);
        } catch (Exception e) {
            log.error("Error toggling night charging", e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to toggle night charging: " + e.getMessage(), null);
        }
    }

    @GetMapping("/current-status")
    @ResponseBody
    public Map<String, Object> getCurrentStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("currentMode", batteryManagementService.isManualIdleActive() ? "idle" : "standard");
        status.put("nightChargingIdle", chargingUtils.isNightChargingIdle());
        status.put("isCharging", batteryManagementService.isForcedChargingActive());
        status.put("currentPrice", marketPriceService.getCurrentlyValidPrice());
        status.put("dropRate", calculateDropRate());
        return status;
    }

    @PostMapping("/toggle-mode")
    @ResponseBody
    public ApiResponse<Void> toggleMode(@RequestBody Map<String, String> request) {
        try {
            String mode = request.get("mode");
            if (mode == null) {
                log.warn("Mode parameter is missing in request body");
                return new ApiResponse<>(false, HttpStatus.BAD_REQUEST, "Mode parameter is required", null);
            }
            if ("idle".equals(mode)) {
                batteryManagementService.activateManualOperatingMode();
                batteryManagementService.setDynamicChargingPoint(0);
                batteryManagementService.setManualIdleActive(true);
                log.info("Switched to idle mode");
                return new ApiResponse<>(true, HttpStatus.OK, "Switched to idle mode", null);
            } else if ("standard".equals(mode)) {
                batteryManagementService.setManualIdleActive(false);
                batteryManagementService.resetToAutomaticMode();
                log.info("Switched to standard mode");
                return new ApiResponse<>(true, HttpStatus.OK, "Switched to standard mode", null);
            } else {
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
            if (charging == null) {
                log.warn("Charging parameter is missing in request body");
                return new ApiResponse<>(false, HttpStatus.BAD_REQUEST, "Charging parameter is required", null);
            }
            if ("start".equals(charging)) {
                batteryManagementService.initCharging(true);
                log.info("Charging started");
                return new ApiResponse<>(true, HttpStatus.OK, "Charging started", null);
            } else if ("stop".equals(charging)) {
                batteryManagementService.resetToAutomaticMode();
                log.info("Charging stopped");
                return new ApiResponse<>(true, HttpStatus.OK, "Charging stopped", null);
            } else {
                log.warn("Invalid charging value: {}", charging);
                return new ApiResponse<>(false, HttpStatus.BAD_REQUEST, "Invalid charging value: " + charging, null);
            }
        } catch (Exception e) {
            log.error("Error toggling charging: {}", e.getMessage(), e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to toggle charging: " + e.getMessage(), null);
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