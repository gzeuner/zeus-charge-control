package de.zeus.power.controller;

import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryStatusResponse;
import de.zeus.power.service.BatteryManagementService;
import de.zeus.power.service.ChargingManagementService;
import de.zeus.power.service.MarketPriceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    // Service to fetch and manage market price data
    @Autowired
    private MarketPriceService marketPriceService;

    // Service to manage battery operations
    @Autowired
    private BatteryManagementService batteryManagementService;

    // Service to manage charging schedules and optimization
    @Autowired
    private ChargingManagementService chargingManagementService;

    // Target state of charge for the battery, injected from configuration
    @Value("${battery.target.stateOfCharge}")
    private int targetStateOfCharge;

    /**
     * Handles GET requests to display the charging status page.
     *
     * @param request The HTTP request object, used to determine the locale.
     * @param lang Optional language parameter to override the default locale.
     * @param model The model to pass data to the view.
     * @return The name of the view template ("chargingStatusView").
     * @throws Exception If JSON serialization fails.
     */
    @GetMapping("/charging-status")
    public String getChargingStatus(HttpServletRequest request, @RequestParam(name = "lang", required = false) String lang, Model model) throws Exception {

        // Determine the locale, defaulting to the request's locale or overridden by the lang parameter
        Locale locale = request.getLocale();
        if (lang != null && !lang.isEmpty()) {
            locale = Locale.forLanguageTag(lang);
        }

        // Add the current language to the model
        model.addAttribute("lang", locale.getLanguage());

        // Fetch and sort market prices by start timestamp
        List<MarketPrice> marketPrices = marketPriceService.getAllMarketPrices()
                .stream()
                .sorted(Comparator.comparingLong(MarketPrice::getStartTimestamp))
                .toList();

        // Retrieve the current battery status
        BatteryStatusResponse batteryStatus = batteryManagementService.getCurrentBatteryStatus();

        // Get the top 5 cheapest future periods, sorted by price and then start time
        List<MarketPrice> cheapestPeriods = marketPriceService.getAllMarketPrices()
                .stream()
                .filter(price -> price.getStartTimestamp() > System.currentTimeMillis()) // Only future periods
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh)
                        .thenComparingLong(MarketPrice::getStartTimestamp)) // Sort by price, then start time
                .limit(5) // Take top 5
                .toList();

        // Find the overall cheapest price across all periods
        MarketPrice cheapestPrice = marketPriceService.getAllMarketPrices()
                .stream()
                .min(Comparator.comparingDouble(MarketPrice::getMarketPrice))
                .orElse(null);

        // Fetch and sort scheduled charging periods by start timestamp
        List<ChargingSchedule> scheduledChargingPeriods = chargingManagementService.getSortedChargingSchedules();

        // JSON mapper to serialize data for client-side use
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

        // Check if the battery configuration is invalid
        boolean batteryNotConfigured = batteryManagementService.isBatteryNotConfigured();
        model.addAttribute("batteryNotConfigured", batteryNotConfigured);

        // Add the night charging idle preference to the model
        model.addAttribute("nightChargingIdle", chargingManagementService.isNightChargingIdle());

        // Return the view template name
        return "chargingStatusView";
    }

    /**
     * Handles POST requests to start charging the battery from the grid.
     *
     * @param model The model to pass data to the view (unused here due to redirect).
     * @return A redirect to the charging status page after a delay.
     */
    @PostMapping("/start-charging")
    public String startCharging(Model model) {
        batteryManagementService.initCharging(true);
        delayRedirect();
        return "redirect:/charging-status";
    }

    /**
     * Handles POST requests to reset the battery to automatic mode.
     *
     * @param model The model to pass data to the view (unused here due to redirect).
     * @return A redirect to the charging status page after a delay.
     */
    @PostMapping("/reset-automatic")
    public String resetToAutomaticMode(Model model) {
        batteryManagementService.resetToAutomaticMode();
        delayRedirect();
        return "redirect:/charging-status";
    }

    /**
     * Handles POST requests to reset the battery to idle mode.
     *
     * @param model The model to pass data to the view (unused here due to redirect).
     * @return A redirect to the charging status page after a delay.
     */
    @PostMapping("/reset-idle")
    public String resetToIdleMode(Model model) {
        batteryManagementService.activateManualOperatingMode();
        batteryManagementService.setDynamicChargingPoint(0);
        delayRedirect();
        return "redirect:/charging-status";
    }

    /**
     * Handles POST requests to toggle the night charging idle behavior via AJAX.
     *
     * @param request JSON request containing the nightChargingIdle boolean.
     * @return An ApiResponse indicating success or failure of the toggle operation as JSON.
     */
    @PostMapping("/toggle-night-charging")
    @ResponseBody
    public ApiResponse<Void> toggleNightCharging(@RequestBody Map<String, Boolean> request) {
        boolean nightChargingIdle = request.getOrDefault("nightChargingIdle", true);
        chargingManagementService.setNightChargingIdle(nightChargingIdle);
        return new ApiResponse<>(true, HttpStatus.OK, "Night charging behavior updated", null);
    }

    /**
     * Introduces a delay before redirecting to allow operations to complete.
     */
    private void delayRedirect() {
        try {
            // Delay for 4 seconds to ensure operations complete before redirect
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}