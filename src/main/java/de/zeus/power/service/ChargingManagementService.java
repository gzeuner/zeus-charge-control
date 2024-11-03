package de.zeus.power.service;

import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.repository.ChargingScheduleRepository;
import de.zeus.power.repository.MarketPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
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
 * ChargeControl - Release V 1.1
 *
 * Author: Guido Zeuner
 */

@Service
public class ChargingManagementService {

    /**
     * Logger instance for logging events in ChargingManagementService.
     */
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

    @Value("${battery.accepted.delay}")
    private int acceptedDelayInMinutes;

    @Value("${battery.target.stateOfCharge}")
    private int targetStateOfCharge;

    @Value("${charging.periods.count:10}")
    private int periodsCount;

    @Value("${charging.periods.maxCount:20}") // Maximale Anzahl von Perioden
    private int maxPeriodsCount;

    private volatile boolean isCharging = false;

    /**
     * Scheduled task to check for the best charging periods based on market prices.
     * This task runs based on a cron expression, which by default is set to run every 30 minutes.
     */
    @Scheduled(cron = "${scheduled.check.cron:0 */30 * * * *}")
    public void scheduleCharging() {
        List<MarketPrice> cheapestPeriods = findCheapestPeriods();
        List<MarketPrice> validPeriods = filterValidPeriods(cheapestPeriods);

        // Dynamische Anpassung der Periodenanzahl
        if (validPeriods.isEmpty() && periodsCount < maxPeriodsCount) {
            logger.info("No valid periods found. Increasing the number of periods to consider.");
            periodsCount = Math.min(periodsCount + 5, maxPeriodsCount); // Begrenzung der Periodenanzahl
            cheapestPeriods = findCheapestPeriods();
            validPeriods = filterValidPeriods(cheapestPeriods);
        }

        if (!validPeriods.isEmpty()) {
            saveChargingSchedule(validPeriods.get(0));
            scheduleNextChargingAttempt(validPeriods, 0);
        } else {
            logger.info("No valid periods found for scheduling.");
        }
    }

    /**
     * Scheduled task to check the battery status and reset charging mode if necessary.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedDelayString = "${scheduled.check.fixedDelay:300000}")
    public void checkAndResetChargingMode() {
        int rsoc = batteryManagementService.getRelativeStateOfCharge();
        if (rsoc >= targetStateOfCharge ||
                (!batteryManagementService.isBatteryCharging()
                        && !batteryManagementService.isBatteryDischarging())) {
            batteryManagementService.resetToAutomaticMode();
        } else {
            logger.info("Battery set to AutomaticMode.");
        }
    }

    /**
     * Filters the list of market price periods to find those that are valid based on the current time and accepted delay.
     *
     * @param periods List of market price periods.
     * @return List of valid market price periods.
     */
    private List<MarketPrice> filterValidPeriods(List<MarketPrice> periods) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        return periods.stream()
                .filter(period -> {
                    LocalDateTime startDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(period.getStartTimestamp()), ZoneId.systemDefault());
                    long minutesDifference = java.time.Duration.between(startDateTime, now).toMinutes();
                    return minutesDifference <= acceptedDelayInMinutes || startDateTime.isAfter(now);
                })
                .collect(Collectors.toList());
    }

    /**
     * Saves a new charging schedule to the repository if it does not already exist.
     *
     * @param period The market price period to save.
     */
    private void saveChargingSchedule(MarketPrice period) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());

        // Delete old entries (older than 2 days)
        LocalDateTime twoDaysAgo = now.minusDays(TWO_DAYS);
        chargingScheduleRepository.findAll().forEach(schedule -> {
            LocalDateTime scheduleStart = LocalDateTime.ofInstant(Instant.ofEpochMilli(schedule.getStartTimestamp()), ZoneId.systemDefault());
            if (scheduleStart.isBefore(twoDaysAgo)) {
                chargingScheduleRepository.delete(schedule);
            }
        });

        // Check whether an identical entry already exists
        boolean exists = chargingScheduleRepository.findAll().stream().anyMatch(schedule ->
                schedule.getStartTimestamp().equals(period.getStartTimestamp()) &&
                        schedule.getEndTimestamp().equals(period.getEndTimestamp()) &&
                        schedule.getPrice().equals(period.getMarketPrice())
        );

        if (!exists) {
            ChargingSchedule schedule = new ChargingSchedule();
            schedule.setStartTimestamp(period.getStartTimestamp());
            schedule.setEndTimestamp(period.getEndTimestamp());
            schedule.setPrice(period.getPriceInCentPerKWh());
            chargingScheduleRepository.save(schedule);
            logger.info("Saved new charging schedule for period starting at {}.", period.getFormattedStartTimestamp());
        } else {
            logger.info("Identical charging schedule already exists. Skipping creation.");
        }
    }

    /**
     * Schedules the next charging attempt based on the list of market price periods.
     *
     * @param periods List of market price periods.
     * @param index The current index in the list of periods.
     */
    private void scheduleNextChargingAttempt(List<MarketPrice> periods, int index) {
        if (index >= periods.size()) {
            logger.info("No more periods to try for charging.");
            return;
        }

        MarketPrice period = periods.get(index);

        // Check whether the period has already been scheduled
        boolean exists = chargingScheduleRepository.findAll().stream().anyMatch(schedule ->
                schedule.getStartTimestamp().equals(period.getStartTimestamp()) &&
                        schedule.getEndTimestamp().equals(period.getEndTimestamp()) &&
                        schedule.getPrice().equals(period.getMarketPrice())
        );

        if (exists) {
            logger.info("Period already scheduled. Skipping: {}", period.getFormattedStartTimestamp());
            scheduleNextChargingAttempt(periods, index + 1);
            return;
        }

        LocalDateTime startDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(period.getStartTimestamp()), ZoneId.systemDefault());
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        long minutesDifference = java.time.Duration.between(startDateTime, now).toMinutes();

        // Calculation of the start date for the charging process
        Date startDate;
        if (minutesDifference <= 0) {
            // Time period is in the future, charging attempt is carried out as planned
            startDate = Date.from(startDateTime.atZone(ZoneId.systemDefault()).toInstant());
        } else {
            // Period is in the past, but within the delay window: Start charging now
            startDate = new Date();
        }

        logger.info("Scheduling charging attempt at: {} for price: {} cent/kWh", period.getFormattedStartTimestamp(), period.getPriceInCentPerKWh());
        taskScheduler.schedule(() -> attemptToStartCharging(periods, index), startDate);
    }

    /**
     * Attempts to start charging during the specified market price period.
     *
     * @param periods List of market price periods.
     * @param index The current index in the list of periods.
     */
    private void attemptToStartCharging(List<MarketPrice> periods, int index) {
        if (!isCharging) {
            boolean chargingStarted = batteryManagementService.initCharging(false);
            if (chargingStarted) {
                isCharging = true;
                logger.info("Charging initiated successfully for period starting at {} for {} cent/kWh",
                        periods.get(index).getFormattedStartTimestamp(), periods.get(index).getPriceInCentPerKWh());

                if (canContinueChargingWithoutInterruption(periods, index)) {
                    logger.info("Next period follows directly. Continuing charging without interruption.");
                } else {
                    scheduleStopChargingBasedOnCurrentPeriod(periods, index);
                }
            } else {
                batteryManagementService.resetToAutomaticMode();
                logger.info("Charging initiation failed for period starting at {}. Trying next cheapest period.",
                        periods.get(index).getFormattedStartTimestamp());
                scheduleNextChargingAttempt(periods, index + 1);
            }
        } else {
            logger.info("Charging is currently active. Scheduled attempt to start charging will wait until current charge cycle completes.");
        }
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

    /**
     * Checks if charging can continue without interruption based on the next market price period.
     *
     * @param periods List of market price periods.
     * @param index The current index in the list of periods.
     * @return true if charging can continue without interruption, false otherwise.
     */
    private boolean canContinueChargingWithoutInterruption(List<MarketPrice> periods, int index) {
        // Checks whether there is a next period and whether this directly follows
        return index + 1 < periods.size() && shouldContinueCharging(periods, index);
    }

    /**
     * Determines if charging should continue based on the time difference between the current and next market price periods.
     *
     * @param periods List of market price periods.
     * @param index The current index in the list of periods.
     * @return true if the next period follows directly and charging should continue, false otherwise.
     */
    private boolean shouldContinueCharging(List<MarketPrice> periods, int index) {
        if (index + 1 >= periods.size()) {
            // No time period available
            return false;
        }

        LocalDateTime currentPeriodEnd = LocalDateTime.ofInstant(Instant.ofEpochMilli(periods.get(index).getEndTimestamp()), ZoneId.systemDefault());
        LocalDateTime nextPeriodStart = LocalDateTime.ofInstant(Instant.ofEpochMilli(periods.get(index + 1).getStartTimestamp()), ZoneId.systemDefault());

        // Calculate the difference between the end of the current period and the start of the next period
        long minutesDifference = java.time.Duration.between(currentPeriodEnd, nextPeriodStart).toMinutes();

        // Allow a tolerance of up to 5 minutes to decide whether loading should continue without interruption
        return minutesDifference >= 0 && minutesDifference <= 5;
    }

    /**
     * Attempts to start fallback charging if no other charging attempts are active.
     */
    private void attemptToStartFallbackCharging() {
        if (!isCharging) {
            boolean chargingStarted = batteryManagementService.initCharging(false);
            if (chargingStarted) {
                isCharging = true;
                logger.info("Fallback charging initiated successfully.");
                // Set a timer to stop charging after a certain time : Charging time 1 hour
                taskScheduler.schedule(this::stopCharging, Date.from(Instant.now().plusSeconds(3600)));
            } else {
                batteryManagementService.resetToAutomaticMode();
                logger.info("Fallback charging initiation failed.");
            }
        } else {
            logger.info("Charging is currently active. Fallback charging will wait until current charge cycle completes.");
        }
    }

    /**
     * Stops the charging process and resets the battery to automatic mode.
     */
    private void stopCharging() {
        boolean chargingStopped = batteryManagementService.resetToAutomaticMode();
        if (chargingStopped) {
            isCharging = false;
            logger.info("Charging successfully stopped.");
        } else {
            logger.error("Failed to stop charging.");
        }
    }

    /**
     * Finds the cheapest market price periods for charging.
     *
     * @return List of the cheapest market price periods.
     */
    private List<MarketPrice> findCheapestPeriods() {
        List<MarketPrice> prices = marketPriceRepository.findAll();
        return prices.stream()
                .sorted(Comparator.comparingDouble(MarketPrice::getMarketPrice))
                .limit(periodsCount)
                .collect(Collectors.toList());
    }


    /**
     * Retrieves and sorts the charging schedules from the repository.
     *
     * @return List of sorted charging schedules.
     */
    public List<ChargingSchedule> getSortedChargingSchedules() {
        return chargingScheduleRepository.findAll().stream()
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp).reversed())
                .collect(Collectors.toList());
    }
}
