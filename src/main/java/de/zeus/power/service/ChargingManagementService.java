    package de.zeus.power.service;

    import de.zeus.power.config.LogFilter;
    import de.zeus.power.entity.ChargingSchedule;
    import de.zeus.power.entity.MarketPrice;
    import de.zeus.power.event.MarketPricesUpdatedEvent;
    import de.zeus.power.repository.ChargingScheduleRepository;
    import de.zeus.power.repository.MarketPriceRepository;
    import de.zeus.power.util.ChargingUtils;
    import de.zeus.power.util.NightConfig;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;
    import org.springframework.beans.factory.annotation.Autowired;
    import org.springframework.beans.factory.annotation.Value;
    import org.springframework.context.event.EventListener;
    import org.springframework.scheduling.TaskScheduler;
    import org.springframework.scheduling.annotation.Scheduled;
    import org.springframework.stereotype.Service;

    import java.text.SimpleDateFormat;
    import java.time.Instant;
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
     *
     */
    @Service
    public class ChargingManagementService {

        private static final Logger logger = LoggerFactory.getLogger(ChargingManagementService.class);
        private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

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

        private final List<ChargingSchedule> daytimeBuffer = new CopyOnWriteArrayList<>();

        private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

        /**
         * Handles the MarketPricesUpdatedEvent and triggers the charging optimization process.
         *
         * @param event MarketPricesUpdatedEvent triggered when market prices are updated.
         */
        @EventListener
        public void onMarketPricesUpdated(MarketPricesUpdatedEvent event) {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Market prices updated event received. Recalculating charging schedule...");

            // Retrieve current RSOC and market prices
            int currentRSOC = batteryManagementService.getRelativeStateOfCharge();
            List<MarketPrice> marketPrices = marketPriceRepository.findAll();

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Current RSOC: %d%%, Total market periods: %d", currentRSOC, marketPrices.size()
            ));

            // Dynamically adjust charging periods based on current RSOC and market data
            adjustChargingPeriodsDynamically(currentRSOC, marketPrices.size(), marketPrices);
            // Precalculate planning
            bufferDaytimeCharging();
            // Perform optimization
            optimizeChargingSchedule();
            // Fallback Reset
            scheduleEndOfNightResetTask();

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Market price update handled and schedules synchronized.");
        }

        @Scheduled(cron = "0 0 * * * ?") // Every full hour
        public void scheduledOptimizeChargingSchedule() {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Scheduled optimization of charging schedule triggered.");

            // Cleaning up expired time windows
            chargingUtils.cleanUpExpiredSchedules(chargingScheduleRepository, scheduledTasks);

            // Call up current RSOC and market prices
            int currentRSOC = batteryManagementService.getRelativeStateOfCharge();
            List<MarketPrice> marketPrices = marketPriceRepository.findAll();

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Current RSOC: %d%%, Total market periods: %d", currentRSOC, marketPrices.size()
            ));

            // Dynamic adjustment of charging periods based on RSOC and market prices
            adjustChargingPeriodsDynamically(currentRSOC, marketPrices.size(), marketPrices);

            // Check and carry out optimization if necessary
            if (shouldOptimize()) {
                optimizeChargingSchedule();
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimization completed successfully.");
            } else {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, "No significant changes detected. Skipping optimization.");
            }
        }

        @Scheduled(fixedRateString = "${battery.automatic.mode.check.interval:300000}")
        private void monitorRSOCAndHandleMode() {
            long currentTimeMillis = System.currentTimeMillis(); // Get timestamp once
            int currentRSOC = batteryManagementService.getRelativeStateOfCharge();
            boolean isNight = chargingUtils.isNight(currentTimeMillis);

            LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                    String.format("Monitoring RSOC: %d%%, Target: %d%%, Night: %b",
                            currentRSOC, targetStateOfCharge, isNight));

            chargingUtils.handleAutomaticModeTransition(currentTimeMillis);
            batteryManagementService.updateRsocHistory(currentTimeMillis, currentRSOC);
        }

        /**
         * Optimizes the charging schedule by combining nighttime and daytime optimizations.
         * Also dynamically evaluates the battery's current relative state of charge (RSOC)
         * to decide if additional charging is required to reach the target RSOC.
         *
         * Steps:
         * 1. Collect and optimize schedules for nighttime and daytime periods.
         * 2. Calculate a dynamic RSOC threshold and compare it with the current RSOC.
         * 3. If the current RSOC is below the threshold, initiate additional charging (daytime only).
         * 4. Synchronize the optimized schedules with the existing ones.
         */
        private void optimizeChargingSchedule() {
            logger.info("Starting optimization of charging schedule...");

            // Initialize a set to hold optimized schedules
            Set<ChargingSchedule> optimizedSchedules = new HashSet<>();

            // Add nighttime charging optimization results
            optimizedSchedules.addAll(optimizeNighttimeCharging());

            // Add daytime charging optimization results
            optimizedSchedules.addAll(optimizeDaytimeCharging());

            // Calculate a dynamic threshold for RSOC and get the current RSOC
            int dynamicThreshold = chargingUtils.calculateDynamicDaytimeThreshold();
            int currentRSOC = batteryManagementService.getRelativeStateOfCharge();

            // Check if it's daytime before evaluating additional charging
            long currentTime = System.currentTimeMillis();
            if (!chargingUtils.isNight(currentTime)) {
                // Check if additional charging is needed based on RSOC and threshold
                if (currentRSOC <= dynamicThreshold) {
                    logger.info("RSOC is below the dynamic threshold (current: {}%, threshold: {}%). Initiating charging to reach target RSOC ({}%).",
                            currentRSOC, dynamicThreshold, targetStateOfCharge);

                    // Add planned charges from the daytime buffer if below the target RSOC
                    if (currentRSOC < targetStateOfCharge) {
                        logger.info("Scheduling additional charging to reach the target RSOC.");

                        // Filter only future schedules from the daytime buffer that are not during nighttime
                        List<ChargingSchedule> futureSchedules = daytimeBuffer.stream()
                                .filter(schedule -> schedule.getStartTimestamp() > currentTime) // Only future schedules
                                .filter(schedule -> !chargingUtils.isNight(schedule.getStartTimestamp())) // Exclude nighttime schedules
                                .toList();

                        optimizedSchedules.addAll(futureSchedules);

                        logger.info("Added {} future schedules from the daytime buffer for additional charging.", futureSchedules.size());
                    }
                } else {
                    logger.info("RSOC is above the dynamic threshold (current: {}%, threshold: {}%). Skipping additional daytime charging.",
                            currentRSOC, dynamicThreshold);
                }
            } else {
                logger.info("Currently nighttime. Skipping evaluation of additional charging.");
            }

            // Synchronize the optimized schedules with the system
            synchronizeSchedules(optimizedSchedules);

            logger.info("Charging schedule optimization completed.");
        }

        /**
         * Buffers daytime charging periods dynamically based on market price analysis.
         * Selects a restricted number of the best charging periods depending on RSOC and dynamic thresholds.
         */
        private void bufferDaytimeCharging() {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Buffering daytime charging periods dynamically based on price analysis.");

            long currentTime = System.currentTimeMillis();

            List<MarketPrice> daytimePeriods = getDaytimePeriods(currentTime);

            if (daytimePeriods.isEmpty()) {
                LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No daytime periods available for buffering.");
                daytimeBuffer.clear();
                return;
            }

            // Calculate dynamic thresholds
            double threshold = chargingUtils.calculateDynamicThreshold(daytimePeriods, priceFlexibilityThreshold);
            double maxAcceptablePrice = chargingUtils.calculateMaxAcceptablePrice(
                    batteryManagementService.getRelativeStateOfCharge(),
                    maxAcceptableMarketPriceInCent
            );

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Dynamic daytime threshold: %.2f cents/kWh, Max acceptable price: %.2f cents/kWh",
                    threshold, maxAcceptablePrice
            ));

            // Dynamically determine the number of periods to select based on RSOC and restrict further
            int currentRSOC = batteryManagementService.getRelativeStateOfCharge();
            int dynamicMaxPeriods = chargingUtils.calculateDynamicMaxChargingPeriods(
                    daytimePeriods.size(),
                    chargingUtils.calculatePriceRange(daytimePeriods),
                    currentRSOC
            );

            // Select the best periods based on price and thresholds
            List<MarketPrice> selectedPeriods = daytimePeriods.stream()
                    .filter(price -> price.getPriceInCentPerKWh() <= threshold)
                    .filter(price -> price.getPriceInCentPerKWh() <= maxAcceptablePrice)
                    .limit(dynamicMaxPeriods)
                    .toList();

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Selected %d daytime periods based on tighter restrictions.", selectedPeriods.size()
            ));

            // Validate and optimize the selected periods
            Set<ChargingSchedule> validatedSchedules = selectedPeriods.stream()
                    .map(price -> chargingUtils.convertToChargingSchedule(price))
                    .filter(schedule -> chargingUtils.isValidSchedule(
                            schedule,
                            currentTime,
                            new HashSet<>(daytimeBuffer),
                            daytimePeriods // Pass daytime periods for dynamic threshold calculation
                    )) // Validate schedule
                    .collect(Collectors.toSet());

            // Update the daytime buffer
            daytimeBuffer.clear();
            daytimeBuffer.addAll(validatedSchedules);

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Buffered %d validated daytime schedules for potential use.", daytimeBuffer.size()
            ));
        }

        private List<MarketPrice> getDaytimePeriods(long currentTime) {
            // Filter market prices that are outside the nighttime window and in the future
            List<MarketPrice> daytimePeriods = marketPriceRepository.findAll().stream()
                    .filter(price -> !chargingUtils.isNight(price.getStartTimestamp())) // Exclude nighttime periods
                    .filter(price -> price.getStartTimestamp() > currentTime) // Only future periods
                    .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh)) // Sort by price
                    .toList();
            return daytimePeriods;
        }

        public void scheduleEndOfNightResetTask() {

            ScheduledFuture<?> existingResetTask = scheduledTasks.get(-1L);
            if (existingResetTask != null) {
                existingResetTask.cancel(false);
            }

            int nightEndHour = NightConfig.getNightEndHour();
            ZoneId zoneId = ZoneId.systemDefault();

            LocalDateTime now = LocalDateTime.now(zoneId);
            LocalDateTime nightEndTime = LocalDateTime.of(now.toLocalDate(), LocalTime.of(nightEndHour, 0));

            if (now.isAfter(nightEndTime)) {
                nightEndTime = nightEndTime.plusDays(1);
            }

            LocalDateTime resetTime = nightEndTime.minusMinutes(15);
            Date resetDate = Date.from(resetTime.atZone(zoneId).toInstant());

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format("Scheduling end-of-night reset for: %s", resetDate));

            ScheduledFuture<?> scheduledResetTask = taskScheduler.schedule(() -> {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Executing end-of-night reset...");
                boolean resetSuccessful = batteryManagementService.resetToAutomaticMode();
                if (resetSuccessful) {
                    LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Successfully reset to automatic mode.");
                } else {
                    LogFilter.log(LogFilter.LOG_LEVEL_ERROR, "Reset to automatic mode failed.");
                }
            }, resetDate);
            scheduledTasks.put(-1L, scheduledResetTask);
        }

        /**
         * Checks whether a new optimization is required.
         *
         * @return True if changes have been detected since the last planning, otherwise False.
         */
        private boolean shouldOptimize() {
            int currentRSOC = batteryManagementService.getRelativeStateOfCharge();
            long currentTime = System.currentTimeMillis();

            // Check whether planned tasks are obsolete
            List<ChargingSchedule> activeSchedules = chargingScheduleRepository.findAll().stream()
                    .filter(schedule -> schedule.getEndTimestamp() > currentTime)
                    .toList();

            boolean hasFutureSchedules = !activeSchedules.isEmpty();

            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format(
                            "Optimization check: RSOC=%d%% FutureSchedules=%b",
                            currentRSOC, hasFutureSchedules
                    )
            );

            // Trigger optimization only if:
            return !hasFutureSchedules || currentRSOC < targetStateOfCharge;
        }

        /**
         * Removes all currently planned charging periods.
         */
        private void removeAllPlannedChargingPeriods() {
            List<ChargingSchedule> existingSchedules = chargingScheduleRepository.findAll().stream()
                    .filter(schedule -> schedule.getEndTimestamp() > System.currentTimeMillis())
                    .toList();

            for (ChargingSchedule schedule : existingSchedules) {
                chargingUtils.cancelTask(schedule.getId(), scheduledTasks);
                chargingScheduleRepository.delete(schedule);

                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Removed planned charging period: %s - %s.",
                        dateFormat.format(new Date(schedule.getStartTimestamp())),
                        dateFormat.format(new Date(schedule.getEndTimestamp()))));
            }
        }

        public void schedulePlannedCharging() {
            List<ChargingSchedule> futureSchedules = chargingScheduleRepository.findAll().stream()
                    .filter(schedule -> schedule.getStartTimestamp() > System.currentTimeMillis())
                    .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                    .toList();

            for (ChargingSchedule schedule : futureSchedules) {
                long scheduleId = schedule.getId();
                Date startTime = new Date(schedule.getStartTimestamp());
                Date endTime = new Date(schedule.getEndTimestamp());

                // Check if a task already exists for this schedule
                ScheduledFuture<?> existingTask = scheduledTasks.get(scheduleId);

                // Cancel only if the existing task differs in timing
                if (existingTask != null && !chargingUtils.isTaskUpToDate(existingTask, schedule)) {
                    chargingUtils.cancelTask(scheduleId, scheduledTasks);
                }

                // Schedule a new task only if no up-to-date task exists
                if (existingTask == null || !chargingUtils.isTaskUpToDate(existingTask, schedule)) {
                    ScheduledFuture<?> scheduledTask = taskScheduler.schedule(() -> {
                        LogFilter.log(
                                LogFilter.LOG_LEVEL_INFO,
                                String.format("Executing scheduled charging for period: %s - %s.",
                                        dateFormat.format(startTime),
                                        dateFormat.format(endTime))
                        );
                        executeChargingTask(startTime, endTime);
                    }, startTime);

                    // Save the task in the map
                    scheduledTasks.put(scheduleId, scheduledTask);

                    LogFilter.log(
                            LogFilter.LOG_LEVEL_INFO,
                            String.format("Scheduled charging task for: %s - %s.",
                                    dateFormat.format(startTime),
                                    dateFormat.format(endTime))
                    );
                }
            }
        }

        public void scheduleStopPlannedCharging() {
            List<ChargingSchedule> futureSchedules = chargingScheduleRepository.findAll().stream()
                    .filter(schedule -> schedule.getStartTimestamp() > System.currentTimeMillis())
                    .sorted(Comparator.comparingLong(ChargingSchedule::getEndTimestamp))
                    .toList();

            for (ChargingSchedule schedule : futureSchedules) {
                long stopTaskId = schedule.getEndTimestamp();
                Date stopTime = new Date(schedule.getEndTimestamp());

                ScheduledFuture<?> existingStopTask = scheduledTasks.get(stopTaskId);
                if (existingStopTask != null && !chargingUtils.isTaskUpToDate(existingStopTask, schedule)) {
                    chargingUtils.cancelTask(stopTaskId, scheduledTasks);
                }
                if (existingStopTask == null || !chargingUtils.isTaskUpToDate(existingStopTask, schedule)) {
                    ScheduledFuture<?> scheduledStopTask = taskScheduler.schedule(() -> {
                        LogFilter.log(
                                LogFilter.LOG_LEVEL_INFO,
                                String.format("Stopping charging as per schedule for period ending at: %s.",
                                        dateFormat.format(stopTime))
                        );
                        batteryManagementService.setDynamicChargingPoint(0);
                    }, stopTime);

                    scheduledTasks.put(stopTaskId, scheduledStopTask);
                    LogFilter.log(
                            LogFilter.LOG_LEVEL_INFO,
                            String.format("Scheduled stop charging task for: %s.", dateFormat.format(stopTime))
                    );
                }
            }
        }


        /**
         * Optimizes daytime charging schedules dynamically based on market price analysis.
         * The method identifies optimal charging periods outside the nighttime window,
         * applies dynamic thresholds, and filters periods with cheaper alternatives to maximize cost efficiency.
         *
         * @return A list of validated and optimized charging schedules for daytime charging.
         */
        private List<ChargingSchedule> optimizeDaytimeCharging() {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing daytime charging dynamically based on price analysis.");

            // Retrieve all market prices and filter for future periods
            List<MarketPrice> allPeriods = marketPriceRepository.findAll();
            List<MarketPrice> daytimePeriods = chargingUtils.filterFuturePeriods(allPeriods).stream()
                    .filter(price -> !chargingUtils.isNight(price.getStartTimestamp())) // Exclude nighttime periods
                    .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh)) // Sort by price
                    .toList();

            if (daytimePeriods.isEmpty()) {
                LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No future daytime periods available for optimization.");
                return Collections.emptyList();
            }

            // Calculate dynamic thresholds for price filtering
            double threshold = chargingUtils.calculateDynamicThreshold(daytimePeriods, priceFlexibilityThreshold);
            double maxAcceptablePrice = chargingUtils.calculateMaxAcceptablePrice(
                    batteryManagementService.getRelativeStateOfCharge(),
                    maxAcceptableMarketPriceInCent
            );

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Dynamic daytime threshold: %.2f cents/kWh, Max acceptable price: %.2f cents/kWh.",
                    threshold, maxAcceptablePrice
            ));

            int dynamicMaxPeriods = chargingUtils.calculateDynamicMaxChargingPeriods(
                    daytimePeriods.size(),
                    chargingUtils.calculatePriceRange(daytimePeriods),
                    batteryManagementService.getRelativeStateOfCharge()
            );

            // Select and filter periods based on thresholds
            List<MarketPrice> filteredPeriods = daytimePeriods.stream()
                    .filter(price -> price.getPriceInCentPerKWh() <= threshold) // Within dynamic threshold
                    .filter(price -> price.getPriceInCentPerKWh() <= maxAcceptablePrice) // Below max acceptable price
                    .filter(price -> chargingUtils.findCheaperFuturePeriod(price, daytimePeriods).isEmpty()) // Exclude if cheaper future period exists
                    .limit(dynamicMaxPeriods)
                    .toList();

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Filtered %d daytime periods after considering thresholds and cheaper alternatives.",
                    filteredPeriods.size()
            ));

            // Validate and optimize the filtered schedules
            Set<ChargingSchedule> validatedSchedules = chargingUtils.validateSchedulesForCheaperOptions(
                    chargingUtils.collectAndOptimizeSchedules(filteredPeriods)
            );

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Validated %d daytime charging schedules after filtering and optimization.",
                    validatedSchedules.size()
            ));

            // Convert the validated schedules to a list and return
            return new ArrayList<>(validatedSchedules);
        }

        private List<ChargingSchedule> optimizeNighttimeCharging() {
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Optimizing nighttime charging dynamically for the current night.");

            // Retrieve all market prices and filter for nighttime periods
            List<MarketPrice> allPeriods = marketPriceRepository.findAll();
            List<MarketPrice> nighttimePeriods = chargingUtils.filterFuturePeriods(allPeriods).stream()
                    .filter(price -> chargingUtils.isNight(price.getStartTimestamp())) // Only nighttime periods
                    .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh)) // Sort by price (ascending)
                    .toList();

            if (nighttimePeriods.isEmpty()) {
                LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No nighttime periods available for optimization.");
                return Collections.emptyList();
            }

            // Use a new method to find the best consecutive nighttime periods
            List<MarketPrice> selectedPeriods = chargingUtils.findBestNightPeriods(nighttimePeriods, maxNighttimePeriods);

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Selected %d nighttime periods for optimization.", selectedPeriods.size()
            ));

            // Ensure the absolute cheapest nighttime period is included
            MarketPrice cheapestNighttime = nighttimePeriods.get(0);
            if (!selectedPeriods.contains(cheapestNighttime)) {
                selectedPeriods.add(cheapestNighttime);
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Ensured inclusion of the cheapest nighttime period.");
            }

            // Convert selected periods into charging schedules
            Set<ChargingSchedule> validatedSchedules = chargingUtils.validateSchedulesForCheaperOptions(
                    chargingUtils.collectAndOptimizeSchedules(selectedPeriods)
            );

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Validated %d nighttime charging schedules after filtering and optimization.",
                    validatedSchedules.size()
            ));

            return new ArrayList<>(validatedSchedules);
        }

        /**
         * Dynamically adjusts the planned charging periods based on the current RSOC and target state of charge.
         * Ensures that new periods are added when needed and unnecessary periods are removed when the RSOC increases.
         * Prevents adding any periods within the nighttime window.
         *
         * @param currentRSOC   The current relative state of charge (RSOC) in percentage.
         * @param totalPeriods  The total number of available periods for charging.
         * @param marketPrices  The list of market prices for optimization.
         */
        private void adjustChargingPeriodsDynamically(int currentRSOC, int totalPeriods, List<MarketPrice> marketPrices) {

            // Calculate required capacity to reach target RSOC
            double requiredCapacity = chargingUtils.calculateRequiredCapacity(currentRSOC);

            if (requiredCapacity <= 0) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO,
                        "RSOC has already reached or exceeded the target. Removing all planned periods.");
                removeAllPlannedChargingPeriods();
                return;
            }

            // Determine the number of required periods
            int requiredPeriods = chargingUtils.calculateRequiredPeriods(requiredCapacity, totalPeriods);

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Calculated %d required periods based on current RSOC (%d%%) and market conditions.",
                    requiredPeriods, currentRSOC));

            // Retrieve and sort existing schedules
            List<ChargingSchedule> existingSchedules = chargingUtils.getFutureChargingSchedules(
                    chargingScheduleRepository.findAll(),
                    System.currentTimeMillis()
            );

            // Remove excess periods
            int excessPeriods = existingSchedules.size() - requiredPeriods;
            if (excessPeriods > 0) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Removing %d excess charging periods as RSOC has increased.", excessPeriods));
                chargingUtils.removeExcessChargingPeriods(existingSchedules, excessPeriods, chargingScheduleRepository, scheduledTasks);
            }

            // Add missing periods, excluding nighttime periods
            int missingPeriods = requiredPeriods - existingSchedules.size();
            if (missingPeriods > 0) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Adding %d new charging periods to meet the energy requirement, excluding nighttime periods.", missingPeriods));
                addAdditionalChargingPeriodsExcludingNighttime(marketPrices, missingPeriods, existingSchedules);
            }
        }


        /**
         * Adds additional charging periods based on market prices and required capacity,
         * ensuring no periods within the nighttime window are added.
         *
         * @param marketPrices       The list of available market prices.
         * @param periodsToAdd       The number of additional periods to add.
         * @param existingSchedules  The list of currently scheduled charging periods.
         */
        private void addAdditionalChargingPeriodsExcludingNighttime(List<MarketPrice> marketPrices, int periodsToAdd, List<ChargingSchedule> existingSchedules) {
            long currentTime = System.currentTimeMillis();

            // Filter market prices to include only future periods outside nighttime
            List<MarketPrice> availablePeriods = marketPrices.stream()
                    .filter(price -> price.getStartTimestamp() > currentTime) // Only future periods
                    .filter(price -> !chargingUtils.isNight(price.getStartTimestamp())) // Exclude nighttime periods
                    .filter(price -> existingSchedules.stream().noneMatch(schedule ->
                            schedule.getStartTimestamp() == price.getStartTimestamp())) // Avoid duplicates
                    .sorted(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh)) // Sort by price
                    .limit(periodsToAdd)
                    .toList();

            if (availablePeriods.isEmpty()) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, "No valid periods found to add. All periods are either duplicates or within nighttime.");
                return;
            }

            for (MarketPrice period : availablePeriods) {
                chargingUtils.createAndLogChargingSchedule(period, chargingScheduleRepository, dateFormat);
            }

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Added %d additional charging periods (excluding nighttime).", availablePeriods.size()));
        }




        /**
         * Executes a scheduled charging task.
         */
        private void executeChargingTask(Date startTime, Date endTime) {
            LogFilter.log(
                    LogFilter.LOG_LEVEL_INFO,
                    String.format(
                            "Scheduled charging task started for period: %s - %s.",
                            dateFormat.format(startTime),
                            dateFormat.format(endTime)
                    )
            );

            batteryManagementService.initCharging(false);
        }

        private void synchronizeSchedules(Set<ChargingSchedule> newSchedules) {
            long currentTime = System.currentTimeMillis();

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Starting synchronization of charging schedules...");
            LogFilter.log(LogFilter.LOG_LEVEL_DEBUG, String.format("Current time for synchronization: %s", dateFormat.format(new Date(currentTime))));

            // Step 1: Retrieve all existing future schedules from the repository
            List<ChargingSchedule> existingSchedules = chargingScheduleRepository.findAll().stream()
                    .filter(schedule -> schedule.getEndTimestamp() > currentTime) // Only consider future schedules
                    .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp)) // Sort by start time
                    .toList();

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Loaded %d existing future schedules for comparison.", existingSchedules.size()));

            // Step 2: Validate the new schedules by removing redundant or invalid entries
            Set<ChargingSchedule> validatedNewSchedules = chargingUtils.validateSchedulesForCheaperOptions(newSchedules);
            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Validated %d new schedules for synchronization.", validatedNewSchedules.size()));

            // Step 3: Retrieve all market prices
            List<MarketPrice> marketPrices = marketPriceRepository.findAll();

            // Step 4: Include the cheapest nighttime period for the entire night window (before and after midnight)
            int nightStartHour = NightConfig.getNightStartHour();
            int nightEndHour = NightConfig.getNightEndHour();

            MarketPrice cheapestFutureNighttime = marketPrices.stream()
                    .filter(price -> {
                        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(price.getStartTimestamp()), ZoneId.systemDefault());
                        return chargingUtils.isNight(price.getStartTimestamp()) ||
                                (time.getHour() >= nightStartHour || time.getHour() < nightEndHour);
                    })
                    .min(Comparator.comparingDouble(MarketPrice::getPriceInCentPerKWh))
                    .orElse(null);

            if (cheapestFutureNighttime != null) {
                LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                        "Cheapest nighttime period for the full night found: %s - %s at %.2f cents/kWh.",
                        dateFormat.format(new Date(cheapestFutureNighttime.getStartTimestamp())),
                        dateFormat.format(new Date(cheapestFutureNighttime.getEndTimestamp())),
                        cheapestFutureNighttime.getPriceInCentPerKWh()
                ));
            } else {
                LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No nighttime periods found for the full night in the market prices.");
            }

            // Step 5: Remove outdated schedules if they are replaced by cheaper alternatives
            for (ChargingSchedule existing : existingSchedules) {
                boolean hasCheaperAlternative = validatedNewSchedules.stream()
                        .anyMatch(newSchedule -> newSchedule.getStartTimestamp().equals(existing.getStartTimestamp())
                                && newSchedule.getEndTimestamp().equals(existing.getEndTimestamp())
                                && newSchedule.getPrice() < existing.getPrice());

                if (hasCheaperAlternative) {
                    chargingUtils.cancelTask(existing.getId(), scheduledTasks);
                    chargingScheduleRepository.delete(existing);
                    LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                            "Removed outdated schedule in favor of a cheaper alternative: %s - %s (%.2f cents/kWh).",
                            dateFormat.format(new Date(existing.getStartTimestamp())),
                            dateFormat.format(new Date(existing.getEndTimestamp())),
                            existing.getPrice()));
                } else {
                    LogFilter.log(LogFilter.LOG_LEVEL_DEBUG, String.format(
                            "No cheaper alternative found for schedule: %s - %s at %.2f cents/kWh.",
                            dateFormat.format(new Date(existing.getStartTimestamp())),
                            dateFormat.format(new Date(existing.getEndTimestamp())),
                            existing.getPrice()));
                }
            }

            // Step 6: Save the validated schedules to the repository, considering dynamic thresholds
            LogFilter.log(LogFilter.LOG_LEVEL_DEBUG, "Saving validated schedules to the repository...");
            saveChargingSchedule(validatedNewSchedules, marketPrices);

            // Step 7: Re-schedule future tasks based on the updated schedules
            LogFilter.log(LogFilter.LOG_LEVEL_DEBUG, "Re-scheduling future tasks based on updated schedules...");
            schedulePlannedCharging();
            scheduleStopPlannedCharging();

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Synchronization of charging schedules completed successfully.");
        }

        /**
         * Saves the selected charging periods to the database, avoiding duplicate, expired, and overpriced entries,
         * and ensuring validation against dynamic thresholds.
         *
         * @param schedules The set of charging schedules to be saved.
         * @param marketPrices The list of market prices used for dynamic threshold validation.
         */
        private void saveChargingSchedule(Set<ChargingSchedule> schedules, List<MarketPrice> marketPrices) {
            long currentTime = System.currentTimeMillis();

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Attempting to save %d charging schedules to the database.", schedules.size()));

            // Filter only future schedules
            Set<ChargingSchedule> futureSchedules = schedules.stream()
                    .filter(schedule -> schedule.getStartTimestamp() > currentTime)
                    .collect(Collectors.toSet());

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                    "Filtered %d future schedules from the provided set.", futureSchedules.size()));

            // Maintain a set of validated schedules to avoid overlaps
            Set<ChargingSchedule> validatedSchedules = new HashSet<>();

            for (ChargingSchedule schedule : futureSchedules) {
                // Step 1: Validate the schedule to ensure it meets all criteria
                if (!chargingUtils.isValidSchedule(schedule, currentTime, validatedSchedules, marketPrices)) {
                    LogFilter.log(LogFilter.LOG_LEVEL_INFO, String.format(
                            "Skipping invalid schedule: %s - %s at %.2f cents/kWh.",
                            dateFormat.format(new Date(schedule.getStartTimestamp())),
                            dateFormat.format(new Date(schedule.getEndTimestamp())),
                            schedule.getPrice()));
                    continue;
                }

                // Step 2: Check for duplicates in the repository
                boolean exists = chargingScheduleRepository.existsByStartEndAndPrice(
                        schedule.getStartTimestamp(),
                        schedule.getEndTimestamp(),
                        schedule.getPrice()
                );

                if (exists) {
                    LogFilter.log(
                            LogFilter.LOG_LEVEL_DEBUG,
                            String.format(
                                    "Skipping duplicate schedule: %s - %s at %.2f cents/kWh.",
                                    dateFormat.format(new Date(schedule.getStartTimestamp())),
                                    dateFormat.format(new Date(schedule.getEndTimestamp())),
                                    schedule.getPrice()
                            )
                    );
                    continue;
                }

                // Step 3: Save the valid schedule to the repository
                try {
                    chargingScheduleRepository.save(schedule);
                    validatedSchedules.add(schedule); // Add to validated set after saving
                    LogFilter.log(
                            LogFilter.LOG_LEVEL_INFO,
                            String.format(
                                    "Successfully saved schedule: %s - %s at %.2f cents/kWh.",
                                    dateFormat.format(new Date(schedule.getStartTimestamp())),
                                    dateFormat.format(new Date(schedule.getEndTimestamp())),
                                    schedule.getPrice()
                            )
                    );
                } catch (Exception e) {
                    LogFilter.log(
                            LogFilter.LOG_LEVEL_ERROR,
                            String.format(
                                    "Failed to save schedule: %s - %s at %.2f cents/kWh. Error: %s",
                                    dateFormat.format(new Date(schedule.getStartTimestamp())),
                                    dateFormat.format(new Date(schedule.getEndTimestamp())),
                                    schedule.getPrice(),
                                    e.getMessage()
                            )
                    );
                }
            }

            LogFilter.log(LogFilter.LOG_LEVEL_INFO, "Completed saving all valid future schedules to the database.");
        }

        /**
         * Retrieves and sorts all existing charging schedules.
         *
         * @return A sorted list of existing charging schedules.
         */
        public List<ChargingSchedule> getSortedChargingSchedules() {
            return chargingScheduleRepository.findAll().stream()
                    .sorted(Comparator.comparingLong(ChargingSchedule::getStartTimestamp))
                    .toList();
        }

    }
