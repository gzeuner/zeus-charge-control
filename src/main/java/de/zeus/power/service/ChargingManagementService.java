package de.zeus.power.service;

import de.zeus.power.config.LogFilter;
import de.zeus.power.entity.ChargingSchedule;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.event.MarketPricesUpdatedEvent;
import de.zeus.power.repository.ChargingScheduleRepository;
import de.zeus.power.repository.MarketPriceRepository;
import de.zeus.power.util.ChargingUtils;
import de.zeus.power.util.NightConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

/**
 * ChargingManagementService handles the scheduling and optimization of charging tasks
 * based on market prices, day/night periods, and the current battery state.
 *
 * Copyright 2024 Guido Zeuner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
 */
@Service
public class ChargingManagementService {

    // Date format for logging timestamps
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    // Cron expression to execute every hour
    private static final String EVERY_HOUR_CRON = "0 0 * * * ?";
    // Unique ID for the end-of-night reset task
    private static final long END_OF_NIGHT_RESET_ID = -1L;
    // Maximum time tolerance in hours for considering cheaper future periods
    private static final int TIME_TOLERANCE_HOURS = 5;

    @Autowired
    private ChargingScheduleRepository chargingScheduleRepository;

    @Autowired
    private MarketPriceRepository marketPriceRepository;

    @Autowired
    private BatteryManagementService batteryManagementService;

    @Autowired
    private TaskScheduler taskScheduler;

    @Autowired
    private ChargingUtils chargingUtils;

    @Value("${battery.target.stateOfCharge:90}")
    private int targetStateOfCharge;

    @Value("${marketdata.acceptable.price.cents:15}")
    private int maxAcceptableMarketPriceInCent;

    @Value("${marketdata.price.flexibility.threshold:10}")
    private double priceFlexibilityThreshold;

    @Value("${nighttime.max.periods:2}")
    private int maxNighttimePeriods;

    @Value("${battery.automatic.mode.check.interval:300000}")
    private long automaticModeCheckInterval;

    private final List<ChargingSchedule> daytimeBuffer = new CopyOnWriteArrayList<>();
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * Handles the MarketPricesUpdatedEvent and triggers the charging optimization process.
     *
     * @param event The MarketPricesUpdatedEvent triggered when market prices are updated.
     */
    @EventListener
    public void onMarketPricesUpdated(MarketPricesUpdatedEvent event) {
        LogFilter.logInfo(ChargingManagementService.class, "Market prices updated event received. Recalculating charging schedule...");
        optimizeChargingProcess();
    }

    /**
     * Scheduled method to optimize the charging schedule every hour.
     */
    @Scheduled(cron = EVERY_HOUR_CRON)
    public void scheduledOptimizeChargingSchedule() {
        LogFilter.logInfo(ChargingManagementService.class, "Scheduled optimization of charging schedule triggered.");
        chargingUtils.cleanUpExpiredSchedules(chargingScheduleRepository, scheduledTasks);
        if (shouldOptimize()) {
            optimizeChargingProcess();
            LogFilter.logInfo(ChargingManagementService.class, "Optimization completed successfully.");
        } else {
            LogFilter.logInfo(ChargingManagementService.class, "No significant changes detected. Skipping optimization.");
        }
    }

    /**
     * Scheduled method to monitor RSOC and handle mode transitions at fixed intervals.
     */
    @Scheduled(fixedRateString = "${battery.automatic.mode.check.interval:300000}")
    private void monitorRsocAndHandleMode() {
        long currentTimeMillis = System.currentTimeMillis();
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        boolean isNight = ChargingUtils.isNight(currentTimeMillis);
        LogFilter.logDebug(ChargingManagementService.class, "Monitoring RSOC: {}%, Target: {}%, Night: {}", currentRsoc, targetStateOfCharge, isNight);
        chargingUtils.handleAutomaticModeTransition(currentTimeMillis);
        batteryManagementService.updateRsocHistory(currentTimeMillis, currentRsoc);
    }

    /**
     * Optimizes the charging schedule by combining nighttime and daytime optimizations.
     */
    private void optimizeChargingSchedule() {
        LogFilter.logInfo(ChargingManagementService.class, "Starting optimization of charging schedule...");
        Set<ChargingSchedule> optimizedSchedules = new HashSet<>();
        optimizedSchedules.addAll(optimizeNighttimeCharging());
        optimizedSchedules.addAll(optimizeDaytimeCharging());

        int dynamicThreshold = chargingUtils.calculateDynamicDaytimeThreshold();
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        long currentTime = System.currentTimeMillis();

        if (!ChargingUtils.isNight(currentTime) && currentRsoc <= dynamicThreshold) {
            LogFilter.logInfo(ChargingManagementService.class, "RSOC is below the dynamic threshold (current: {}%, threshold: {}%). Initiating charging to reach target RSOC ({}%).",
                    currentRsoc, dynamicThreshold, targetStateOfCharge);
            if (currentRsoc < targetStateOfCharge) {
                LogFilter.logInfo(ChargingManagementService.class, "Scheduling additional charging to reach the target RSOC.");
                optimizedSchedules.addAll(getFutureDaytimeSchedules(currentTime));
            }
        } else {
            LogFilter.logInfo(ChargingManagementService.class, currentRsoc > dynamicThreshold
                    ? "RSOC is above the dynamic threshold (current: {}%, threshold: {}%). Skipping additional daytime charging."
                    : "Currently nighttime. Skipping evaluation of additional charging.", currentRsoc, dynamicThreshold);
        }

        synchronizeSchedules(optimizedSchedules);
        LogFilter.logInfo(ChargingManagementService.class, "Charging schedule optimization completed.");
    }

    /**
     * Buffers daytime charging periods dynamically based on market price analysis.
     *
     * @param marketPrices List of available market prices to determine the dynamic threshold.
     */
    private void bufferDaytimeCharging(List<MarketPrice> marketPrices) {
        LogFilter.logInfo(ChargingManagementService.class, "Buffering daytime charging periods dynamically based on price analysis.");
        long currentTime = System.currentTimeMillis();
        List<MarketPrice> daytimePeriods = getDaytimePeriods(currentTime);

        if (daytimePeriods.isEmpty()) {
            LogFilter.logWarn(ChargingManagementService.class, "No daytime periods available for buffering.");
            daytimeBuffer.clear();
            return;
        }

        double dynamicMaxPrice = calculateDynamicMaxPrice(marketPrices);
        double threshold = chargingUtils.calculateDynamicThreshold(daytimePeriods, priceFlexibilityThreshold);
        double maxAcceptablePrice = Math.min(dynamicMaxPrice, chargingUtils.calculateMaxAcceptablePrice(batteryManagementService.getRelativeStateOfCharge(), maxAcceptableMarketPriceInCent));
        logThresholds(threshold, maxAcceptablePrice);

        int dynamicMaxPeriods = chargingUtils.calculateDynamicMaxChargingPeriods(daytimePeriods.size(),
                chargingUtils.calculatePriceRange(daytimePeriods), batteryManagementService.getRelativeStateOfCharge());

        Set<ChargingSchedule> validatedSchedules = daytimePeriods.stream()
                .filter(p -> p.getPriceInCentPerKWh() <= threshold && p.getPriceInCentPerKWh() <= maxAcceptablePrice)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(dynamicMaxPeriods)
                .map(chargingUtils::convertToChargingSchedule)
                .filter(s -> chargingUtils.isValidSchedule(s, currentTime, new HashSet<>(daytimeBuffer), daytimePeriods))
                .collect(Collectors.toSet());

        daytimeBuffer.clear();
        daytimeBuffer.addAll(validatedSchedules);
        LogFilter.logInfo(ChargingManagementService.class, "Buffered {} validated daytime schedules for potential use.", validatedSchedules.size());
    }

    /**
     * Calculates a dynamic maximum acceptable price based on available market prices.
     *
     * @param marketPrices List of available market prices.
     * @return The dynamically calculated maximum acceptable price in cent/kWh.
     */
    private double calculateDynamicMaxPrice(List<MarketPrice> marketPrices) {
        if (marketPrices == null || marketPrices.isEmpty()) {
            LogFilter.logWarn(ChargingManagementService.class, "No market prices available for dynamic max price calculation. Using default: {}", maxAcceptableMarketPriceInCent);
            return maxAcceptableMarketPriceInCent;
        }

        double medianPrice = marketPrices.stream()
                .mapToDouble(MarketPrice::getPriceInCentPerKWh)
                .sorted()
                .skip(marketPrices.size() / 2)
                .findFirst()
                .orElse(maxAcceptableMarketPriceInCent);

        double dynamicMaxPrice = Math.min(medianPrice * 1.2, maxAcceptableMarketPriceInCent);
        LogFilter.logInfo(ChargingManagementService.class, "Calculated dynamic max acceptable price: {} cent/kWh (Median: {} cent/kWh)", dynamicMaxPrice, medianPrice);
        return dynamicMaxPrice;
    }

    /**
     * Retrieves future daytime periods excluding nighttime.
     *
     * @param currentTime The current timestamp in milliseconds.
     * @return A list of daytime market price periods.
     */
    private List<MarketPrice> getDaytimePeriods(long currentTime) {
        long toleranceWindow = currentTime + (TIME_TOLERANCE_HOURS * 60 * 60 * 1000);
        return marketPriceRepository.findAll().stream()
                .filter(p -> !ChargingUtils.isNight(p.getStartTimestamp()) &&
                        p.getStartTimestamp() > currentTime &&
                        p.getStartTimestamp() <= toleranceWindow)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .collect(Collectors.toList());
    }

    /**
     * Central method to perform the full charging optimization process.
     */
    private void optimizeChargingProcess() {
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        List<MarketPrice> marketPrices = marketPriceRepository.findAll();
        logRsocAndPeriods(currentRsoc, marketPrices.size());

        if (currentRsoc >= targetStateOfCharge) {
            LogFilter.logInfo(ChargingManagementService.class, "RSOC ({}) is at or above target ({}). Removing all planned periods and skipping further scheduling.", currentRsoc, targetStateOfCharge);
            removeAllPlannedChargingPeriods();
            return;
        }

        double dynamicMaxPrice = calculateDynamicMaxPrice(marketPrices);
        adjustChargingPeriodsDynamically(currentRsoc, marketPrices.size(), marketPrices, dynamicMaxPrice);
        bufferDaytimeCharging(marketPrices);
        optimizeChargingSchedule();
        scheduleEndOfNightResetTask();
        LogFilter.logInfo(ChargingManagementService.class, "Market price update handled and schedules synchronized.");
    }

    /**
     * Schedules a task to reset the battery mode at the end of the night period.
     */
    public void scheduleEndOfNightResetTask() {
        ScheduledFuture<?> existingTask = scheduledTasks.get(END_OF_NIGHT_RESET_ID);
        if (existingTask != null) existingTask.cancel(false);

        LocalDateTime resetTime = LocalDateTime.now(ZoneId.systemDefault())
                .with(LocalTime.of(NightConfig.getNightEndHour(), 0))
                .plusDays(LocalDateTime.now().getHour() >= NightConfig.getNightEndHour() ? 1 : 0)
                .minusMinutes(15);
        Date resetDate = Date.from(resetTime.atZone(ZoneId.systemDefault()).toInstant());

        LogFilter.logInfo(ChargingManagementService.class, "Scheduling end-of-night reset for: {}", resetDate);
        scheduledTasks.put(END_OF_NIGHT_RESET_ID, taskScheduler.schedule(() -> {
            LogFilter.logInfo(ChargingManagementService.class, "Executing end-of-night reset...");
            batteryManagementService.setManualIdleActive(false);
            logResetResult(batteryManagementService.resetToAutomaticMode());
        }, resetDate));
    }

    /**
     * Checks if optimization is needed based on RSOC and future schedules.
     *
     * @return True if optimization is required, false otherwise.
     */
    private boolean shouldOptimize() {
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        long currentTime = System.currentTimeMillis();
        boolean hasFutureSchedules = chargingScheduleRepository.findAll().stream().anyMatch(s -> s.getEndTimestamp() > currentTime);

        LogFilter.logInfo(ChargingManagementService.class, "Optimization check: RSOC={}%, FutureSchedules={}", currentRsoc, hasFutureSchedules);
        return !hasFutureSchedules || currentRsoc < targetStateOfCharge;
    }

    /**
     * Removes all planned charging periods that are still in the future.
     */
    private void removeAllPlannedChargingPeriods() {
        chargingScheduleRepository.findAll().stream()
                .filter(s -> s.getEndTimestamp() > System.currentTimeMillis())
                .forEach(s -> {
                    chargingUtils.cancelTask(s.getId(), scheduledTasks);
                    chargingScheduleRepository.delete(s);
                    LogFilter.logInfo(ChargingManagementService.class, "Removed planned charging period: {} - {}.",
                            DATE_FORMAT.format(new Date(s.getStartTimestamp())),
                            DATE_FORMAT.format(new Date(s.getEndTimestamp())));
                });
    }

    /**
     * Schedules all planned charging tasks.
     */
    public void schedulePlannedCharging() {
        chargingScheduleRepository.findAll().stream()
                .filter(s -> s.getStartTimestamp() > System.currentTimeMillis())
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .forEach(this::scheduleChargingTask);
    }

    /**
     * Schedules stop tasks for all planned charging periods.
     */
    public void scheduleStopPlannedCharging() {
        if(!ChargingUtils.isNight(System.currentTimeMillis())) {
            return;
        }
        chargingScheduleRepository.findAll().stream()
                .filter(s -> s.getStartTimestamp() > System.currentTimeMillis())
                .sorted(Comparator.comparingLong(ChargingSchedule::getEndTimestamp))
                .forEach(this::scheduleStopTask);
    }

    /**
     * Optimizes daytime charging schedules based on market prices.
     *
     * @return A list of optimized daytime charging schedules.
     */
    private List<ChargingSchedule> optimizeDaytimeCharging() {
        LogFilter.logInfo(ChargingManagementService.class, "Optimizing daytime charging dynamically based on price analysis.");
        List<MarketPrice> daytimePeriods = chargingUtils.filterFuturePeriods(marketPriceRepository.findAll()).stream()
                .filter(p -> !ChargingUtils.isNight(p.getStartTimestamp()))
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .collect(Collectors.toList());

        if (daytimePeriods.isEmpty()) {
            LogFilter.logWarn(ChargingManagementService.class, "No future daytime periods available for optimization.");
            return Collections.emptyList();
        }

        double threshold = chargingUtils.calculateDynamicThreshold(daytimePeriods, priceFlexibilityThreshold);
        double maxAcceptablePrice = chargingUtils.calculateMaxAcceptablePrice(batteryManagementService.getRelativeStateOfCharge(), maxAcceptableMarketPriceInCent);
        logThresholds(threshold, maxAcceptablePrice);

        int dynamicMaxPeriods = chargingUtils.calculateDynamicMaxChargingPeriods(daytimePeriods.size(),
                chargingUtils.calculatePriceRange(daytimePeriods), batteryManagementService.getRelativeStateOfCharge());

        long currentTime = System.currentTimeMillis();
        long toleranceWindow = currentTime + (TIME_TOLERANCE_HOURS * 60 * 60 * 1000);

        List<ChargingSchedule> optimizedSchedules = daytimePeriods.stream()
                .filter(p -> p.getPriceInCentPerKWh() <= threshold && p.getPriceInCentPerKWh() <= maxAcceptablePrice)
                .filter(p -> p.getStartTimestamp() <= toleranceWindow)
                .filter(p -> chargingUtils.findCheaperFuturePeriod(p, daytimePeriods).isEmpty())
                .limit(dynamicMaxPeriods)
                .map(chargingUtils::convertToChargingSchedule)
                .collect(Collectors.toList());

        LogFilter.logInfo(ChargingManagementService.class, "Optimized {} daytime charging schedules.", optimizedSchedules.size());
        return optimizedSchedules;
    }

    /**
     * Optimizes nighttime charging schedules based on market prices and RSOC needs with a dynamic price threshold.
     *
     * @return A list of optimized nighttime charging schedules.
     */
    private List<ChargingSchedule> optimizeNighttimeCharging() {
        LogFilter.logInfo(ChargingManagementService.class, "Optimizing nighttime charging dynamically for the current night.");
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        if (currentRsoc >= targetStateOfCharge) {
            LogFilter.logInfo(ChargingManagementService.class, "RSOC ({}) is at or above target ({}). Skipping nighttime charging.", currentRsoc, targetStateOfCharge);
            return Collections.emptyList();
        }

        long currentTime = System.currentTimeMillis();
        LocalDateTime nightStartTime = LocalDateTime.now(ZoneId.systemDefault())
                .with(LocalTime.of(22, 0))
                .plusDays(LocalDateTime.now().getHour() < 22 ? 0 : 1);
        LocalDateTime nightEndTime = LocalDateTime.now(ZoneId.systemDefault())
                .with(LocalTime.of(NightConfig.getNightEndHour(), 0))
                .plusDays(LocalDateTime.now().getHour() >= NightConfig.getNightEndHour() ? 1 : 0);
        long nightStartTimestamp = nightStartTime.atZone(ZoneId.systemDefault()).toEpochSecond() * 1000;
        long nightEndTimestamp = nightEndTime.atZone(ZoneId.systemDefault()).toEpochSecond() * 1000;

        List<MarketPrice> allPrices = marketPriceRepository.findAll();
        List<MarketPrice> nighttimePeriods = allPrices.stream()
                .filter(p -> p.getStartTimestamp() >= nightStartTimestamp && p.getStartTimestamp() <= nightEndTimestamp)
                .collect(Collectors.toList());

        if (nighttimePeriods.isEmpty()) {
            LogFilter.logWarn(ChargingManagementService.class, "No nighttime periods available for optimization between {} and {}.",
                    DATE_FORMAT.format(new Date(nightStartTimestamp)),
                    DATE_FORMAT.format(new Date(nightEndTimestamp)));
            return Collections.emptyList();
        }

        LogFilter.logInfo(ChargingManagementService.class, "Available nighttime periods:");
        nighttimePeriods.forEach(p -> LogFilter.logInfo(ChargingManagementService.class, " - {} - {} at {} cent/kWh",
                DATE_FORMAT.format(new Date(p.getStartTimestamp())),
                DATE_FORMAT.format(new Date(p.getEndTimestamp())),
                p.getPriceInCentPerKWh()));

        double dynamicMaxPrice = calculateDynamicMaxPrice(allPrices);
        int requiredPeriods = chargingUtils.calculateRequiredPeriods(chargingUtils.calculateRequiredCapacity(currentRsoc), allPrices.size());
        int periodsToPlan = Math.min(Math.max(requiredPeriods, 1), maxNighttimePeriods); // Mindestens 1, maximal 2
        LogFilter.logInfo(ChargingManagementService.class, "Required periods based on RSOC ({}%) and market conditions: {}, planning: {}", currentRsoc, requiredPeriods, periodsToPlan);

        List<MarketPrice> selectedPeriods = nighttimePeriods.stream()
                .filter(p -> p.getPriceInCentPerKWh() <= dynamicMaxPrice)
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(periodsToPlan)
                .collect(Collectors.toList());

        if (selectedPeriods.isEmpty()) {
            LogFilter.logWarn(ChargingManagementService.class, "No nighttime periods below dynamic max price ({} cent/kWh) until end of night.", dynamicMaxPrice);
            return Collections.emptyList();
        }

        LogFilter.logInfo(ChargingManagementService.class, "Selected nighttime periods:");
        selectedPeriods.forEach(p -> LogFilter.logInfo(ChargingManagementService.class, " - {} - {} at {} cent/kWh",
                DATE_FORMAT.format(new Date(p.getStartTimestamp())),
                DATE_FORMAT.format(new Date(p.getEndTimestamp())),
                p.getPriceInCentPerKWh()));

        List<ChargingSchedule> optimizedSchedules = selectedPeriods.stream()
                .map(chargingUtils::convertToChargingSchedule)
                .collect(Collectors.toList());

        LogFilter.logInfo(ChargingManagementService.class, "Optimized {} nighttime charging schedules.", optimizedSchedules.size());
        return optimizedSchedules;
    }

    /**
     * Dynamically adjusts charging periods based on current RSOC, market conditions, and dynamic max price.
     *
     * @param currentRsoc The current relative state of charge.
     * @param totalPeriods The total number of available market periods.
     * @param marketPrices The list of market prices to consider.
     * @param dynamicMaxPrice The dynamically calculated maximum acceptable price.
     */
    private void adjustChargingPeriodsDynamically(int currentRsoc, int totalPeriods, List<MarketPrice> marketPrices, double dynamicMaxPrice) {
        double requiredCapacity = chargingUtils.calculateRequiredCapacity(currentRsoc);
        if (requiredCapacity <= 0) {
            LogFilter.logInfo(ChargingManagementService.class, "RSOC has already reached or exceeded the target. Removing all planned periods.");
            removeAllPlannedChargingPeriods();
            return;
        }

        int requiredPeriods = chargingUtils.calculateRequiredPeriods(requiredCapacity, totalPeriods);
        LogFilter.logInfo(ChargingManagementService.class, "Calculated {} required periods based on current RSOC ({}%) and market conditions.", requiredPeriods, currentRsoc);

        List<ChargingSchedule> existingSchedules = chargingUtils.getFutureChargingSchedules(chargingScheduleRepository.findAll(), System.currentTimeMillis());
        int excessPeriods = existingSchedules.size() - requiredPeriods;
        if (excessPeriods > 0) {
            LogFilter.logInfo(ChargingManagementService.class, "Removing {} excess charging periods as RSOC has increased.", excessPeriods);
            chargingUtils.removeExcessChargingPeriods(existingSchedules, excessPeriods, chargingScheduleRepository, scheduledTasks);
        }

        int missingPeriods = requiredPeriods - existingSchedules.size();
        if (missingPeriods > 0) {
            LogFilter.logInfo(ChargingManagementService.class, "Adding {} new charging periods to meet the energy requirement, excluding nighttime periods, below dynamic max price {}.", missingPeriods, dynamicMaxPrice);
            addAdditionalChargingPeriodsExcludingNighttime(marketPrices, missingPeriods, existingSchedules, dynamicMaxPrice);
        }
    }

    /**
     * Adds additional charging periods excluding nighttime based on market prices and dynamic max price.
     *
     * @param marketPrices The list of available market prices.
     * @param periodsToAdd The number of periods to add.
     * @param existingSchedules The list of current schedules to avoid duplicates.
     * @param dynamicMaxPrice The dynamically calculated maximum acceptable price.
     */
    private void addAdditionalChargingPeriodsExcludingNighttime(List<MarketPrice> marketPrices, int periodsToAdd, List<ChargingSchedule> existingSchedules, double dynamicMaxPrice) {
        long currentTime = System.currentTimeMillis();
        long toleranceWindow = currentTime + (TIME_TOLERANCE_HOURS * 60 * 60 * 1000);
        List<MarketPrice> availablePeriods = marketPrices.stream()
                .filter(p -> p.getStartTimestamp() > currentTime && p.getStartTimestamp() <= toleranceWindow)
                .filter(p -> !ChargingUtils.isNight(p.getStartTimestamp())) // Strenger Nachtfilter
                .filter(p -> p.getPriceInCentPerKWh() <= dynamicMaxPrice)
                .filter(p -> existingSchedules.stream().noneMatch(s -> Objects.equals(s.getStartTimestamp(), p.getStartTimestamp())))
                .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .limit(periodsToAdd)
                .collect(Collectors.toList());

        if (availablePeriods.isEmpty()) {
            LogFilter.logInfo(ChargingManagementService.class, "No valid daytime periods found to add within tolerance window below dynamic max price {}.", dynamicMaxPrice);
            return;
        }

        availablePeriods.forEach(p -> chargingUtils.createAndLogChargingSchedule(p, chargingScheduleRepository, DATE_FORMAT));
        LogFilter.logInfo(ChargingManagementService.class, "Added {} additional daytime charging periods below dynamic max price {}.", availablePeriods.size(), dynamicMaxPrice);
    }

    /**
     * Executes a scheduled charging task.
     *
     * @param startTime The start time of the charging period.
     * @param endTime The end time of the charging period.
     */
    private void executeChargingTask(Date startTime, Date endTime) {
        LogFilter.logInfo(ChargingManagementService.class, "Scheduled charging task started for period: {} - {}.", DATE_FORMAT.format(startTime), DATE_FORMAT.format(endTime));
        batteryManagementService.initCharging(false);
    }

    /**
     * Synchronizes new schedules with existing ones, ensuring cheapest options are retained.
     *
     * @param newSchedules The set of new schedules to synchronize.
     */
    private void synchronizeSchedules(Set<ChargingSchedule> newSchedules) {
        long currentTime = System.currentTimeMillis();
        LogFilter.logInfo(ChargingManagementService.class, "Starting synchronization of charging schedules...");
        LogFilter.logDebug(ChargingManagementService.class, "Current time for synchronization: {}", DATE_FORMAT.format(new Date(currentTime)));

        List<ChargingSchedule> existingSchedules = chargingScheduleRepository.findAll().stream()
                .filter(s -> s.getEndTimestamp() > currentTime)
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());

        Set<ChargingSchedule> validatedNewSchedules = chargingUtils.validateSchedulesForCheaperOptions(newSchedules);
        List<MarketPrice> marketPrices = marketPriceRepository.findAll();

        removeOutdatedSchedules(existingSchedules, validatedNewSchedules);
        saveChargingSchedule(validatedNewSchedules, marketPrices);

        schedulePlannedCharging();
        scheduleStopPlannedCharging();
        LogFilter.logInfo(ChargingManagementService.class, "Synchronization of charging schedules completed successfully.");
    }

    /**
     * Saves validated charging schedules to the repository.
     *
     * @param schedules The set of schedules to save.
     * @param marketPrices The list of market prices for validation.
     */
    private void saveChargingSchedule(Set<ChargingSchedule> schedules, List<MarketPrice> marketPrices) {
        long currentTime = System.currentTimeMillis();
        LogFilter.logInfo(ChargingManagementService.class, "Attempting to save {} charging schedules to the database.", schedules.size());

        Set<ChargingSchedule> validatedSchedules = new HashSet<>();
        schedules.stream()
                .filter(s -> s.getStartTimestamp() > currentTime)
                .filter(s -> chargingUtils.isValidSchedule(s, currentTime, validatedSchedules, marketPrices))
                .filter(s -> !chargingScheduleRepository.existsByStartEndAndPrice(s.getStartTimestamp(), s.getEndTimestamp(), s.getPrice()))
                .forEach(s -> saveSchedule(s, validatedSchedules));

        LogFilter.logInfo(ChargingManagementService.class, "Completed saving all valid future schedules to the database.");
    }

    /**
     * Retrieves all charging schedules sorted by start timestamp.
     *
     * @return A sorted list of charging schedules.
     */
    public List<ChargingSchedule> getSortedChargingSchedules() {
        return chargingScheduleRepository.findAll().stream()
                .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                .collect(Collectors.toList());
    }

    // Helper Methods

    /**
     * Logs the current RSOC and number of market periods.
     *
     * @param rsoc The current relative state of charge.
     * @param periods The total number of market periods.
     */
    private void logRsocAndPeriods(int rsoc, int periods) {
        LogFilter.logInfo(ChargingManagementService.class, "Current RSOC: {}%, Total market periods: {}", rsoc, periods);
    }

    /**
     * Logs the dynamic threshold and maximum acceptable price for daytime charging.
     *
     * @param threshold The calculated dynamic threshold.
     * @param maxPrice The maximum acceptable price.
     */
    private void logThresholds(double threshold, double maxPrice) {
        LogFilter.logInfo(ChargingManagementService.class, "Dynamic daytime threshold: {:.2f} cents/kWh, Max acceptable price: {:.2f} cents/kWh", threshold, maxPrice);
    }

    /**
     * Logs the result of the reset operation.
     *
     * @param success True if the reset was successful, false otherwise.
     */
    private void logResetResult(boolean success) {
        LogFilter.log(ChargingManagementService.class, success ? LogFilter.LOG_LEVEL_INFO : LogFilter.LOG_LEVEL_ERROR,
                success ? "Successfully reset to automatic mode." : "Reset to automatic mode failed.");
    }

    /**
     * Retrieves future daytime schedules from the buffer.
     *
     * @param currentTime The current timestamp in milliseconds.
     * @return A list of future daytime schedules.
     */
    private List<ChargingSchedule> getFutureDaytimeSchedules(long currentTime) {
        List<ChargingSchedule> futureSchedules = daytimeBuffer.stream()
                .filter(s -> s.getStartTimestamp() > currentTime && !ChargingUtils.isNight(s.getStartTimestamp()))
                .collect(Collectors.toList());
        LogFilter.logInfo(ChargingManagementService.class, "Added {} future schedules from the daytime buffer for additional charging.", futureSchedules.size());
        return futureSchedules;
    }

    /**
     * Schedules a charging task for a given schedule.
     *
     * @param schedule The charging schedule to schedule.
     */
    private void scheduleChargingTask(ChargingSchedule schedule) {
        long id = schedule.getId();
        Date startTime = new Date(schedule.getStartTimestamp());
        Date endTime = new Date(schedule.getEndTimestamp());
        ScheduledFuture<?> existingTask = scheduledTasks.get(id);

        if (existingTask == null || !chargingUtils.isTaskUpToDate(existingTask, schedule)) {
            if (existingTask != null) chargingUtils.cancelTask(id, scheduledTasks);
            ScheduledFuture<?> task = taskScheduler.schedule(() -> executeChargingTask(startTime, endTime), startTime);
            scheduledTasks.put(id, task);
            LogFilter.logInfo(ChargingManagementService.class, "Scheduled charging task for: {} - {}.", DATE_FORMAT.format(startTime), DATE_FORMAT.format(endTime));
        }
    }

    /**
     * Schedules a stop task for a given schedule.
     *
     * @param schedule The charging schedule to stop.
     */
    private void scheduleStopTask(ChargingSchedule schedule) {
        long stopId = schedule.getEndTimestamp();
        Date stopTime = new Date(stopId);
        ScheduledFuture<?> existingTask = scheduledTasks.get(stopId);

        if (existingTask == null || !chargingUtils.isTaskUpToDate(existingTask, schedule)) {
            if (existingTask != null) chargingUtils.cancelTask(stopId, scheduledTasks);
            ScheduledFuture<?> task = taskScheduler.schedule(() -> {
                LogFilter.logInfo(ChargingManagementService.class, "Stopping charging as per schedule for period ending at: {}.", DATE_FORMAT.format(stopTime));
                batteryManagementService.setDynamicChargingPoint(0);
            }, stopTime);
            scheduledTasks.put(stopId, task);
            LogFilter.logInfo(ChargingManagementService.class, "Scheduled stop charging task for: {}.", DATE_FORMAT.format(stopTime));
        }
    }

    /**
     * Ensures the cheapest nighttime period is included in the schedules if RSOC requires charging.
     *
     * @param schedules The set of schedules to update.
     * @param marketPrices The list of market prices to check.
     */
    private void ensureCheapestNighttimePeriod(Set<ChargingSchedule> schedules, List<MarketPrice> marketPrices) {
        int currentRsoc = batteryManagementService.getRelativeStateOfCharge();
        if (currentRsoc >= targetStateOfCharge) {
            LogFilter.logInfo(ChargingManagementService.class, "RSOC ({}) is at or above target ({}). No need to ensure cheapest nighttime period.", currentRsoc, targetStateOfCharge);
            return;
        }

        MarketPrice cheapestNight = marketPrices.stream()
                .filter(p -> ChargingUtils.isNight(p.getStartTimestamp()))
                .min(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                .orElse(null);
        if (cheapestNight != null && schedules.stream().noneMatch(s -> Objects.equals(s.getStartTimestamp(), cheapestNight.getStartTimestamp()))) {
            schedules.add(chargingUtils.convertToChargingSchedule(cheapestNight));
            LogFilter.logInfo(ChargingManagementService.class, "Cheapest nighttime period for the full night found: {} - {} at {}.",
                    DATE_FORMAT.format(new Date(cheapestNight.getStartTimestamp())),
                    DATE_FORMAT.format(new Date(cheapestNight.getEndTimestamp())), cheapestNight.getPriceInCentPerKWh());
        } else if (cheapestNight == null) {
            LogFilter.logWarn(ChargingManagementService.class, "No nighttime periods found for the full night in the market prices.");
        }
    }

    /**
     * Removes outdated schedules if cheaper alternatives exist.
     *
     * @param existing The list of existing schedules.
     * @param newSchedules The set of new schedules to compare against.
     */
    private void removeOutdatedSchedules(List<ChargingSchedule> existing, Set<ChargingSchedule> newSchedules) {
        existing.forEach(e -> {
            if (newSchedules.stream().anyMatch(n -> n.getStartTimestamp().equals(e.getStartTimestamp()) &&
                    n.getEndTimestamp().equals(e.getEndTimestamp()) && n.getPrice() < e.getPrice())) {
                chargingUtils.cancelTask(e.getId(), scheduledTasks);
                chargingScheduleRepository.delete(e);
                LogFilter.logInfo(ChargingManagementService.class, "Removed outdated schedule in favor of a cheaper alternative: {} - {} ({} cents/kWh).",
                        DATE_FORMAT.format(new Date(e.getStartTimestamp())),
                        DATE_FORMAT.format(new Date(e.getEndTimestamp())), e.getPrice());
            } else {
                LogFilter.logDebug(ChargingManagementService.class, "No cheaper alternative found for schedule: {} - {} at {} cents/kWh.",
                        DATE_FORMAT.format(new Date(e.getStartTimestamp())),
                        DATE_FORMAT.format(new Date(e.getEndTimestamp())), e.getPrice());
            }
        });
    }

    /**
     * Saves a single schedule to the repository and adds it to validated schedules.
     *
     * @param schedule The schedule to save.
     * @param validated The set of validated schedules to update.
     */
    private void saveSchedule(ChargingSchedule schedule, Set<ChargingSchedule> validated) {
        try {
            chargingScheduleRepository.save(schedule);
            validated.add(schedule);
            LogFilter.logInfo(ChargingManagementService.class, "Successfully saved schedule: {} - {} at {:.2f} cents/kWh.",
                    DATE_FORMAT.format(new Date(schedule.getStartTimestamp())),
                    DATE_FORMAT.format(new Date(schedule.getEndTimestamp())), schedule.getPrice());
        } catch (Exception e) {
            LogFilter.logError(ChargingManagementService.class, "Failed to save schedule: {} - {} at {} cents/kWh. Error: {}",
                    DATE_FORMAT.format(new Date(schedule.getStartTimestamp())),
                    DATE_FORMAT.format(new Date(schedule.getEndTimestamp())), schedule.getPrice(), e.getMessage());
        }
    }
}