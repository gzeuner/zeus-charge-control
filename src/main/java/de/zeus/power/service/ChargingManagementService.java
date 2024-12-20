package de.zeus.power.service;

import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.event.MarketPricesUpdatedEvent;
import de.zeus.power.repository.ChargingScheduleRepository;
import de.zeus.power.repository.MarketPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Copyright 2024 Guido Zeuner
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * © 2024 - Guido Zeuner - https://tiny-tool.de
 *
 */

@Service
public class ChargingManagementService {

    private static final Logger logger = LoggerFactory.getLogger(ChargingManagementService.class);
    public static final int TWO_DAYS = 2;

    @Autowired
    private MarketPriceRepository marketPriceRepository;

    @Autowired
    private ChargingScheduleRepository chargingScheduleRepository;

    @Autowired
    private BatteryManagementService batteryManagementService;

    @Autowired
    private TaskScheduler taskScheduler;

    @Value("${battery.target.stateOfCharge}")
    private int targetStateOfCharge;

    @Value("${marketdata.acceptable.price.cents:15}")
    private int maxAcceptableMarketPriceInCent;

    @EventListener
    public void handleMarketPricesUpdated(MarketPricesUpdatedEvent event) {
        scheduleCharging();
    }

    public void scheduleCharging() {
        logger.info("Starting charging schedule process...");

        // Update the market prices
        List<MarketPrice> updatedPrices = marketPriceRepository.findAll();
        if (updatedPrices.isEmpty()) {
            logger.warn("No market prices available after update. Skipping optimization.");
            return;
        }

        // Filter periods by the maximum acceptable price and ensure they are in the future
        List<MarketPrice> filteredPeriods = updatedPrices.stream()
                .filter(price -> price.getPriceInCentPerKWh() <= maxAcceptableMarketPriceInCent && isFuture(price.getStartTimestamp()))
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .collect(Collectors.toList());

        if (filteredPeriods.isEmpty()) {
            logger.warn("No acceptable charging periods found. Skipping scheduling.");
            return;
        }

        // Identify the cheapest period
        MarketPrice cheapestPeriod = filteredPeriods.get(0);
        logger.info("Cheapest period: {} at {} cent/kWh",
                cheapestPeriod.getFormattedStartTimestamp(), cheapestPeriod.getPriceInCentPerKWh());

        // Initialize the relevant periods list with the cheapest period
        List<MarketPrice> relevantPeriods = List.of(cheapestPeriod);

        // Find the directly next period (if it exists)
        MarketPrice nextPeriod = filteredPeriods.stream()
                .filter(price -> Objects.equals(price.getStartTimestamp(), cheapestPeriod.getEndTimestamp()))
                .findFirst()
                .orElse(null);

        if (nextPeriod != null) {
            logger.info("Adding the next period: {} at {} cent/kWh",
                    nextPeriod.getFormattedStartTimestamp(), nextPeriod.getPriceInCentPerKWh());
            relevantPeriods = List.of(cheapestPeriod, nextPeriod);
        }

        // Save the load plan for all relevant periods
        saveChargingSchedule(relevantPeriods);

        // Schedule charging attempts for all selected periods
        scheduleNextChargingAttempt(relevantPeriods, 0);
    }


    private boolean isFuture(long timestamp) {
        return Instant.ofEpochMilli(timestamp).isAfter(Instant.now());
    }

    @Scheduled(fixedDelayString = "${scheduled.check.fixedDelay:300000}")
    public void checkAndResetChargingMode() {
        int rsoc = batteryManagementService.getRelativeStateOfCharge();

        if (rsoc >= targetStateOfCharge) {
            logger.info("RSOC is at or above target: {}%. Resetting to automatic mode.", targetStateOfCharge);
            batteryManagementService.resetToAutomaticMode();

            // Immediately trigger a new charging plan
            logger.info("Triggering a new charging schedule as RSOC reached the target.");
            scheduleCharging();
        } else {
            logger.info("RSOC is below target: {}%. No action required.", targetStateOfCharge);
        }
    }

    private void saveChargingSchedule(List<MarketPrice> periods) {
        // Delete past charging schedules
        logger.info("Deleting charging schedules from the past...");
        long now = Instant.now().toEpochMilli();

        List<ChargingSchedule> pastSchedules = chargingScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getEndTimestamp() < now)
                .collect(Collectors.toList());

        if (!pastSchedules.isEmpty()) {
            chargingScheduleRepository.deleteAll(pastSchedules);
            logger.info("Deleted {} past charging schedules.", pastSchedules.size());
        } else {
            logger.info("No past charging schedules found to delete.");
        }

        // Save new load plans
        logger.info("Saving new charging schedules...");
        periods.forEach(period -> {
            ChargingSchedule schedule = new ChargingSchedule();
            schedule.setStartTimestamp(period.getStartTimestamp());
            schedule.setEndTimestamp(period.getEndTimestamp());
            schedule.setPrice(period.getPriceInCentPerKWh());
            chargingScheduleRepository.save(schedule);
            logger.info("Saved new charging schedule for period starting at {}.", period.getFormattedStartTimestamp());
        });

        logger.info("New charging schedules saved successfully.");
    }

    private void scheduleNextChargingAttempt(List<MarketPrice> periods, int index) {
        logger.debug("scheduleNextChargingAttempt called with index {} out of {} periods.", index, periods.size());

        if (index >= periods.size()) {
            logger.info("No more periods to try for charging.");
            return;
        }

        MarketPrice period = periods.get(index);

        logger.debug("Evaluating period: {} with price {} cent/kWh", period.getFormattedStartTimestamp(), period.getPriceInCentPerKWh());

        if (period.getPriceInCentPerKWh() > maxAcceptableMarketPriceInCent) {
            logger.info("Market price {} cent/kWh exceeds acceptable limit of {} cent/kWh. Skipping period: {}",
                    period.getPriceInCentPerKWh(), maxAcceptableMarketPriceInCent, period.getFormattedStartTimestamp());
            scheduleNextChargingAttempt(periods, index + 1);
            return;
        }

        LocalDateTime startDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(period.getStartTimestamp()), ZoneId.systemDefault());
        Date startDate = startDateTime.isAfter(LocalDateTime.now(ZoneId.systemDefault()))
                ? Date.from(startDateTime.atZone(ZoneId.systemDefault()).toInstant())
                : null;

        if (startDate == null) {
            logger.warn("Cannot schedule charging attempt for past time period: {}", period.getFormattedStartTimestamp());
            scheduleNextChargingAttempt(periods, index + 1);
            return;
        }

        logger.info("Scheduling charging attempt at: {} for price: {} cent/kWh", period.getFormattedStartTimestamp(), period.getPriceInCentPerKWh());

        try {
            taskScheduler.schedule(() -> {
                logger.info("Executing charging attempt for period: {}", period.getFormattedStartTimestamp());
                attemptToStartCharging(periods, index);
            }, startDate);

            logger.debug("Task successfully scheduled for execution at: {}", startDateTime);
        } catch (Exception e) {
            logger.error("Failed to schedule charging attempt for period: {}", period.getFormattedStartTimestamp(), e);
        }
    }


    private void attemptToStartCharging(List<MarketPrice> periods, int index) {
        int rsoc = batteryManagementService.getRelativeStateOfCharge();

        // Check whether the charge level is already sufficient
        if (rsoc >= targetStateOfCharge) {
            logger.info("RSOC is already at {}%, which meets or exceeds the target of {}%. Skipping charging.", rsoc, targetStateOfCharge);

            // Update market prices and start new planning
            logger.info("Updating market prices and rescheduling...");
            scheduleCharging();
            return;
        }

        boolean chargingStarted = batteryManagementService.initCharging(false);
        if (chargingStarted) {
            logger.info("Charging initiated successfully for period starting at {} for {} cent/kWh",
                    periods.get(index).getFormattedStartTimestamp(), periods.get(index).getPriceInCentPerKWh());

            if (canContinueChargingWithoutInterruption(periods, index)) {
                logger.info("Next period follows directly. Continuing charging without interruption.");
            } else {
                scheduleStopChargingBasedOnCurrentPeriod(periods, index);
            }
        } else {
            logger.error("Charging initiation failed or skipped for period starting at {}. Trying next cheapest period.",
                    periods.get(index).getFormattedStartTimestamp());
            batteryManagementService.resetToAutomaticMode();
            scheduleNextChargingAttempt(periods, index + 1);
        }
    }

    private boolean canContinueChargingWithoutInterruption(List<MarketPrice> periods, int index) {
        return index + 1 < periods.size() && shouldContinueCharging(periods, index);
    }

    private boolean shouldContinueCharging(List<MarketPrice> periods, int index) {
        if (index + 1 >= periods.size()) return false;

        LocalDateTime currentPeriodEnd = LocalDateTime.ofInstant(Instant.ofEpochMilli(periods.get(index).getEndTimestamp()), ZoneId.systemDefault());
        LocalDateTime nextPeriodStart = LocalDateTime.ofInstant(Instant.ofEpochMilli(periods.get(index + 1).getStartTimestamp()), ZoneId.systemDefault());
        long minutesDifference = java.time.Duration.between(currentPeriodEnd, nextPeriodStart).toMinutes();

        return minutesDifference >= 0 && minutesDifference <= 5;
    }

    private void stopCharging() {
        boolean chargingStopped = batteryManagementService.resetToAutomaticMode();
        if (chargingStopped) {
            logger.info("Charging successfully stopped.");
        } else {
            logger.error("Failed to stop charging.");
        }
    }

    public List<ChargingSchedule> getSortedChargingSchedules() {
        return chargingScheduleRepository.findAll().stream()
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());
    }

    /**
     * Schedules the stopping of the charging process based on the current market price period.
     *
     * @param periods List of market price periods.
     * @param currentIndex The current index in the list of periods.
     */
    private void scheduleStopChargingBasedOnCurrentPeriod(List<MarketPrice> periods, int currentIndex) {
        LocalDateTime stopTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(periods.get(currentIndex).getEndTimestamp()), ZoneId.systemDefault());
        Date stopDate = Date.from(stopTime.atZone(ZoneId.systemDefault()).toInstant());
        taskScheduler.schedule(this::stopCharging, stopDate);
        logger.info("Charging scheduled to stop at {}", stopTime);
    }

}
