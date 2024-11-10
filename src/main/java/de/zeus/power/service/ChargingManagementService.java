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

    @Value("${battery.accepted.delay}")
    private int acceptedDelayInMinutes;

    @Value("${battery.target.stateOfCharge}")
    private int targetStateOfCharge;

    @Value("${charging.periods.count:10}")
    private int periodsCount;

    @Value("${charging.periods.maxCount:20}")
    private int maxPeriodsCount;

    private volatile boolean isCharging = false;

    @Scheduled(cron = "${scheduled.check.cron:0 */30 * * * *}")
    public void scheduleCharging() {
        List<MarketPrice> cheapestPeriods = findCheapestPeriods();
        List<MarketPrice> validPeriods = filterValidPeriods(cheapestPeriods);

        if (validPeriods.isEmpty() && periodsCount < maxPeriodsCount) {
            logger.info("No valid periods found. Increasing the number of periods to consider.");
            periodsCount = Math.min(periodsCount + 5, maxPeriodsCount);
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
     * Additional scheduler for charging planning at midnight to take advantage of the best night-time prices.
     */
    @Scheduled(cron = "0 0 0 * * *") // Runs daily at midnight
    public void optimizeNightChargingSchedule() {
        List<MarketPrice> updatedPrices = marketPriceRepository.findAll();
        MarketPrice bestNightPeriod = updatedPrices.stream()
                .filter(price -> isNightHour(price.getStartTimestamp()))
                .min(Comparator.comparingDouble(MarketPrice::getMarketPrice))
                .orElse(null);

        if (bestNightPeriod != null && isCheaperThanCurrentSchedule(bestNightPeriod)) {
            saveChargingSchedule(bestNightPeriod);
            scheduleNextChargingAttempt(List.of(bestNightPeriod), 0);
            logger.info("Charging process rescheduled for more cost-effective night-time hours by {} to {} cents/kWh",
                    bestNightPeriod.getFormattedStartTimestamp(), bestNightPeriod.getPriceInCentPerKWh());
        } else {
            logger.info("No better price found in the night hours.");
        }
    }

    /**
     * Additional scheduler for charging planning in the daytime to take advantage of the best day-time prices.
     */
    @Scheduled(cron = "${scheduled.job.cron:0 00 10 * * *}") // Runs daily at 10:00 a.m.
    public void optimizeDayChargingSchedule() {
        List<MarketPrice> updatedPrices = marketPriceRepository.findAll();
        MarketPrice bestDayPeriod = updatedPrices.stream()
                .filter(price -> isDayHour(price.getStartTimestamp()))
                .min(Comparator.comparingDouble(MarketPrice::getMarketPrice))
                .orElse(null);

        if (bestDayPeriod != null && isCheaperThanCurrentSchedule(bestDayPeriod)) {
            saveChargingSchedule(bestDayPeriod);
            scheduleNextChargingAttempt(List.of(bestDayPeriod), 0);
            logger.info("Charging process rescheduled for more cost-effective daytime hours by {} to {} cents/kWh",
                    bestDayPeriod.getFormattedStartTimestamp(), bestDayPeriod.getPriceInCentPerKWh());
        } else {
            logger.info("No better price found in the daytime hours.");
        }
    }

    private boolean isNightHour(long timestamp) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        return dateTime.getHour() >= 2 && dateTime.getHour() <= 6;
    }

    private boolean isDayHour(long timestamp) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        return dateTime.getHour() >= 10 && dateTime.getHour() <= 14;
    }

    private boolean isCheaperThanCurrentSchedule(MarketPrice nightPeriod) {
        List<ChargingSchedule> schedules = getSortedChargingSchedules();
        return schedules.isEmpty() || nightPeriod.getMarketPrice() < schedules.get(0).getPrice();
    }

    @Scheduled(fixedDelayString = "${scheduled.check.fixedDelay:300000}")
    public void checkAndResetChargingMode() {
        int rsoc = batteryManagementService.getRelativeStateOfCharge();
        if (rsoc >= targetStateOfCharge ||
                (!batteryManagementService.isBatteryCharging() && !batteryManagementService.isBatteryDischarging())) {
            batteryManagementService.resetToAutomaticMode();
        } else {
            logger.info("Battery set to AutomaticMode.");
        }
    }

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

    private void saveChargingSchedule(MarketPrice period) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        LocalDateTime twoDaysAgo = now.minusDays(TWO_DAYS);
        chargingScheduleRepository.findAll().forEach(schedule -> {
            LocalDateTime scheduleStart = LocalDateTime.ofInstant(Instant.ofEpochMilli(schedule.getStartTimestamp()), ZoneId.systemDefault());
            if (scheduleStart.isBefore(twoDaysAgo)) {
                chargingScheduleRepository.delete(schedule);
            }
        });

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

    private void scheduleNextChargingAttempt(List<MarketPrice> periods, int index) {
        if (index >= periods.size()) {
            logger.info("No more periods to try for charging.");
            return;
        }

        MarketPrice period = periods.get(index);
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
        Date startDate = startDateTime.isAfter(LocalDateTime.now(ZoneId.systemDefault()))
                ? Date.from(startDateTime.atZone(ZoneId.systemDefault()).toInstant())
                : new Date();

        logger.info("Scheduling charging attempt at: {} for price: {} cent/kWh", period.getFormattedStartTimestamp(), period.getPriceInCentPerKWh());
        taskScheduler.schedule(() -> attemptToStartCharging(periods, index), startDate);
    }

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
            isCharging = false;
            logger.info("Charging successfully stopped.");
        } else {
            logger.error("Failed to stop charging.");
        }
    }

    private List<MarketPrice> findCheapestPeriods() {
        List<MarketPrice> prices = marketPriceRepository.findAll();
        return prices.stream()
                .sorted(Comparator.comparingDouble(MarketPrice::getMarketPrice))
                .limit(periodsCount)
                .collect(Collectors.toList());
    }

    public List<ChargingSchedule> getSortedChargingSchedules() {
        return chargingScheduleRepository.findAll().stream()
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp).reversed())
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
