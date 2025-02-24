package de.zeus.power.controller;

import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryStatusResponse;
import de.zeus.power.service.BatteryManagementService;
import de.zeus.power.service.ChargingManagementService;
import de.zeus.power.service.MarketPriceService;
import de.zeus.power.util.ChargingUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Controller responsible for managing the charging status view and related operations.
 * Handles requests for displaying battery status, market prices, and charging schedules,
 * as well as controlling battery charging modes.
 */
@Controller
public class ChargingStatusController {

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

    /**
     * Handles GET requests to display the charging status page with grid charging info in a tooltip.
     *
     * @param request The HTTP request object, used to determine the locale.
     * @param lang Optional language parameter to override the default locale.
     * @param model The model to pass data to the view.
     * @return The name of the view template ("chargingStatusView").
     * @throws Exception If JSON serialization fails.
     */
    @GetMapping("/charging-status")
    public String getChargingStatus(HttpServletRequest request, @RequestParam(name = "lang", required = false) String lang, Model model) throws Exception {

        Locale locale = request.getLocale();
        if (lang != null && !lang.isEmpty()) {
            locale = Locale.forLanguageTag(lang);
        }
        model.addAttribute("lang", locale.getLanguage());

        List<MarketPrice> marketPrices = marketPriceService.getAllMarketPrices()
                .stream()
                .sorted(Comparator.comparingLong(MarketPrice::getStartTimestamp))
                .toList();

        BatteryStatusResponse batteryStatus = batteryManagementService.getCurrentBatteryStatus();

        List<MarketPrice> cheapestPeriods = marketPriceService.getAllMarketPrices()
                .stream()
                .filter(price -> price.getStartTimestamp() > System.currentTimeMillis())
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh)
                        .thenComparingLong(MarketPrice::getStartTimestamp))
                .limit(5)
                .toList();

        MarketPrice cheapestPrice = marketPriceService.getAllMarketPrices()
                .stream()
                .min(Comparator.comparingDouble(MarketPrice::getMarketPrice))
                .orElse(null);

        List<ChargingSchedule> scheduledChargingPeriods = chargingManagementService.getSortedChargingSchedules();

        ObjectMapper mapper = new ObjectMapper();
        model.addAttribute("marketPrices", marketPrices);
        model.addAttribute("batteryStatus", batteryStatus);
        model.addAttribute("cheapestPeriods", cheapestPeriods);
        model.addAttribute("scheduledChargingPeriods", scheduledChargingPeriods);
        model.addAttribute("cheapestPrice", cheapestPrice);
        model.addAttribute("targetStateOfCharge", targetStateOfCharge);
        model.addAttribute("marketPricesJson", mapper.writeValueAsString(marketPrices));
        model.addAttribute("cheapestPeriodsJson", mapper.writeValueAsString(cheapestPeriods));
        model.addAttribute("scheduledChargingPeriodsJson", mapper.writeValueAsString(scheduledChargingPeriods));

        boolean batteryNotConfigured = batteryManagementService.isBatteryNotConfigured();
        model.addAttribute("batteryNotConfigured", batteryNotConfigured);

        model.addAttribute("nightChargingIdle", chargingUtils.isNightChargingIdle());

        // Add grid charging info for tooltip
        Double dropRate = calculateDropRate();
        Double currentPrice = marketPriceService.getCurrentPrice(); // Use the new method
        Double estimatedTimeToTarget = calculateEstimatedTimeToTarget();
        model.addAttribute("dropRate", dropRate);
        model.addAttribute("currentPrice", currentPrice);
        model.addAttribute("estimatedTimeToTarget", estimatedTimeToTarget);
        model.addAttribute("currentTime", System.currentTimeMillis());

        // Build tooltip text with current price prioritized
        StringBuilder tooltipText = new StringBuilder();
        if (currentPrice != null) {
            tooltipText.append("<strong>").append(messageSource.getMessage("currentPriceLabel", null, locale))
                    .append(":</strong> ").append(String.format("%.2f cent/kWh", currentPrice))
                    .append("<br>");
        } else {
            tooltipText.append("<strong>").append(messageSource.getMessage("currentPriceLabel", null, locale))
                    .append(":</strong> ").append("N/A")
                    .append("<br>");
        }
        if (dropRate != null) {
            tooltipText.append("<strong>").append(messageSource.getMessage("dropRateLabel", null, locale))
                    .append(":</strong> ").append(String.format("%.2f %%/h", dropRate))
                    .append("<br>");
        }
        if (estimatedTimeToTarget != null) {
            tooltipText.append("<strong>").append(messageSource.getMessage("estimatedTimeToTargetLabel", null, locale))
                    .append(":</strong> ").append(String.format("%.2f h", estimatedTimeToTarget));
        }
        if (tooltipText.length() == 0) {
            tooltipText.append(messageSource.getMessage("noGridChargingInfo", null, locale));
        }
        model.addAttribute("gridChargingTooltip", tooltipText.toString());

        return "chargingStatusView";
    }

    @PostMapping("/start-charging")
    public String startCharging(Model model) {
        batteryManagementService.initCharging(true);
        delayRedirect();
        return "redirect:/charging-status";
    }

    @PostMapping("/reset-automatic")
    public String resetToAutomaticMode(Model model) {
        batteryManagementService.resetToAutomaticMode();
        delayRedirect();
        return "redirect:/charging-status";
    }

    @PostMapping("/reset-idle")
    public String resetToIdleMode(Model model) {
        batteryManagementService.activateManualOperatingMode();
        batteryManagementService.setDynamicChargingPoint(0);
        delayRedirect();
        return "redirect:/charging-status";
    }

    @PostMapping("/toggle-night-charging")
    @ResponseBody
    public ApiResponse<Void> toggleNightCharging(@RequestBody Map<String, Boolean> request) {
        boolean nightChargingIdle = request.getOrDefault("nightChargingIdle", true);
        chargingUtils.setNightChargingIdle(nightChargingIdle);
        return new ApiResponse<>(true, HttpStatus.OK, "Night charging behavior updated", null);
    }

    /**
     * Calculates the battery drop rate in percentage per hour based on RSOC history.
     *
     * @return The drop rate in %/h or null if insufficient data.
     */
    private Double calculateDropRate() {
        List<Map.Entry<Long, Integer>> history = batteryManagementService.getRsocHistory();
        if (history.size() < 2) return null;
        Map.Entry<Long, Integer> oldest = history.get(0);
        Map.Entry<Long, Integer> latest = history.get(history.size() - 1);
        long timeDiffMinutes = (latest.getKey() - oldest.getKey()) / 60000;
        if (timeDiffMinutes <= 0) return null;
        return (double) (oldest.getValue() - latest.getValue()) / (timeDiffMinutes / 60.0);
    }

    /**
     * Estimates the time required to reach the target state of charge.
     *
     * @return The estimated time in hours or null if already at or above target.
     */
    private Double calculateEstimatedTimeToTarget() {
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        if (currentRsoc >= targetStateOfCharge) return null;
        double requiredCapacity = chargingUtils.calculateRequiredCapacity(currentRsoc);
        if (requiredCapacity <= 0 || chargingPointInWatt <= 0) return null;
        return requiredCapacity / chargingPointInWatt;
    }

    /**
     * Introduces a delay before redirecting to allow operations to complete.
     */
    private void delayRedirect() {
        try {
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}