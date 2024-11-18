package de.zeus.power.controller;

import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.model.BatteryStatusResponse;
import de.zeus.power.service.BatteryManagementService;
import de.zeus.power.service.ChargingManagementService;
import de.zeus.power.service.MarketPriceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import javax.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import com.fasterxml.jackson.databind.ObjectMapper;

@Controller
public class ChargingStatusController {

    @Autowired
    private MarketPriceService marketPriceService;

    @Autowired
    private BatteryManagementService batteryManagementService;

    @Autowired
    private ChargingManagementService chargingManagementService;

    @Value("${battery.target.stateOfCharge}")
    private int targetStateOfCharge;

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

        // Get the top 10 cheapest periods based on start time (future periods only)
        List<MarketPrice> cheapestPeriods = marketPriceService.getAllMarketPrices()
                .stream()
                .filter(price -> price.getStartTimestamp() > System.currentTimeMillis()) // Only future periods
                .sorted(Comparator.comparingLong(MarketPrice::getStartTimestamp)) // Sort by start time
                .limit(10) // Take top 10 based on start time
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

        return "chargingStatusView";
    }

    @PostMapping("/start-charging")
    public String startCharging(Model model) {
        batteryManagementService.initCharging(true);
        return "redirect:/charging-status";
    }

    @PostMapping("/reset-automatic")
    public String resetToAutomaticMode(Model model) {
        batteryManagementService.resetToAutomaticMode();
        return "redirect:/charging-status";
    }
}

