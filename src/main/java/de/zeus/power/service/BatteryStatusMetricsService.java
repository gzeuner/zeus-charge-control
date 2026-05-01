package de.zeus.power.service;

import de.zeus.power.model.BatteryCapacitySnapshot;
import de.zeus.power.model.BatteryStatusMetrics;
import de.zeus.power.model.BatteryStatusSample;
import de.zeus.power.model.BatteryStatusResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralizes UI-oriented battery metric calculations such as drop rate and ETA.
 */
@Service
public class BatteryStatusMetricsService {
    private static final long MIN_HISTORY_WINDOW_MS = 2 * 60_000L;
    private static final int POWER_DEADBAND_W = 50;
    private static final double MIN_EFFECTIVE_RATE_PER_HOUR = 0.01;

    private enum TrendDirection {
        CHARGING,
        DISCHARGING,
        IDLE
    }

    private final BatteryManagementService batteryManagementService;
    private final BatteryCapacityService batteryCapacityService;
    private final SeasonalTargetStateOfChargeService seasonalTargetStateOfChargeService;

    public BatteryStatusMetricsService(BatteryManagementService batteryManagementService,
                                       BatteryCapacityService batteryCapacityService,
                                       SeasonalTargetStateOfChargeService seasonalTargetStateOfChargeService) {
        this.batteryManagementService = batteryManagementService;
        this.batteryCapacityService = batteryCapacityService;
        this.seasonalTargetStateOfChargeService = seasonalTargetStateOfChargeService;
    }

    public BatteryStatusMetrics calculateCurrentMetrics() {
        BatteryStatusResponse currentStatus = batteryManagementService.getCurrentBatteryStatus();
        if (currentStatus == null) {
            return new BatteryStatusMetrics(null, null);
        }

        TrendDirection trend = classifyTrend(currentStatus);
        BatteryCapacitySnapshot capacitySnapshot = batteryCapacityService.calculateSnapshot(currentStatus);
        double totalCapacityWh = Math.max(0, capacitySnapshot.totalCapacityWh());
        double remainingCapacityWh = Math.max(0, capacitySnapshot.remainingCapacityWh());
        Double signedSocRatePerHour = resolveSignedSocRatePerHour(currentStatus, trend, totalCapacityWh);
        Double dropRate = calculateDropRatePerHour(trend, signedSocRatePerHour, totalCapacityWh, remainingCapacityWh);
        Double eta = calculateEstimatedTimeToTargetHours(
                currentStatus.getRsoc(),
                seasonalTargetStateOfChargeService.getCurrentTargetStateOfCharge(),
                signedSocRatePerHour
        );

        return new BatteryStatusMetrics(dropRate, eta);
    }

    private Double resolveSignedSocRatePerHour(BatteryStatusResponse currentStatus,
                                               TrendDirection trend,
                                               double capacityWh) {
        Double historyRate = normalizeRateForTrend(calculateHistoryBasedSocRatePerHour(trend, capacityWh), trend);
        Double liveRate = normalizeRateForTrend(calculateLiveSocRatePerHour(currentStatus, trend, capacityWh), trend);

        if (trend != TrendDirection.IDLE && liveRate != null) {
            if (historyRate == null || Math.abs(historyRate) < MIN_EFFECTIVE_RATE_PER_HOUR) {
                return liveRate;
            }
        }

        if (historyRate != null) {
            return historyRate;
        }

        return liveRate;
    }

    private Double normalizeRateForTrend(Double signedSocRatePerHour, TrendDirection trend) {
        if (signedSocRatePerHour == null) return null;

        return switch (trend) {
            case CHARGING -> signedSocRatePerHour > 0 ? signedSocRatePerHour : null;
            case DISCHARGING -> signedSocRatePerHour < 0 ? signedSocRatePerHour : null;
            case IDLE -> Math.abs(signedSocRatePerHour) < MIN_EFFECTIVE_RATE_PER_HOUR ? 0.0 : null;
        };
    }

    private Double calculateHistoryBasedSocRatePerHour(TrendDirection trend, double capacityWh) {
        List<BatteryStatusSample> history = batteryManagementService.getBatteryStatusHistory();
        if (history.size() < 2) return null;

        List<BatteryStatusSample> window = latestContiguousWindow(history, trend);
        if (window.size() < 2) return null;

        BatteryStatusSample oldest = window.get(0);
        BatteryStatusSample latest = window.get(window.size() - 1);
        long elapsedMs = latest.timestamp() - oldest.timestamp();
        if (elapsedMs < MIN_HISTORY_WINDOW_MS) return null;

        double hours = elapsedMs / 3_600_000.0;
        if (hours <= 0.0) return null;

        if (capacityWh > 0
                && oldest.remainingCapacityWh() != null
                && latest.remainingCapacityWh() != null) {
            double deltaWh = latest.remainingCapacityWh() - oldest.remainingCapacityWh();
            return (deltaWh * 100.0 / capacityWh) / hours;
        }

        return (latest.rsoc() - oldest.rsoc()) / hours;
    }

    private List<BatteryStatusSample> latestContiguousWindow(List<BatteryStatusSample> history, TrendDirection trend) {
        List<BatteryStatusSample> reversed = new ArrayList<>();
        for (int index = history.size() - 1; index >= 0; index--) {
            BatteryStatusSample sample = history.get(index);
            if (classifyTrend(sample) != trend) break;
            reversed.add(sample);
        }

        List<BatteryStatusSample> window = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            window.add(reversed.get(index));
        }
        return window;
    }

    private Double calculateLiveSocRatePerHour(BatteryStatusResponse currentStatus,
                                               TrendDirection trend,
                                               double capacityWh) {
        if (capacityWh <= 0) return null;

        int powerW = Math.abs(currentStatus.getPacTotalW());
        if (powerW < POWER_DEADBAND_W) {
            return trend == TrendDirection.IDLE ? 0.0 : null;
        }

        return switch (trend) {
            case CHARGING -> powerW * 100.0 / capacityWh;
            case DISCHARGING -> -powerW * 100.0 / capacityWh;
            case IDLE -> 0.0;
        };
    }

    private Double calculateDropRatePerHour(TrendDirection trend,
                                            Double signedSocRatePerHour,
                                            double totalCapacityWh,
                                            double remainingCapacityWh) {
        if (signedSocRatePerHour == null) return null;
        if (totalCapacityWh <= 0 || remainingCapacityWh <= 0) return null;
        if (trend == TrendDirection.DISCHARGING || signedSocRatePerHour < 0) {
            double rate = Math.abs(signedSocRatePerHour) * totalCapacityWh / remainingCapacityWh;
            return Double.isFinite(rate) ? rate : null;
        }
        if (trend == TrendDirection.IDLE && Math.abs(signedSocRatePerHour) < MIN_EFFECTIVE_RATE_PER_HOUR) {
            return 0.0;
        }
        return null;
    }

    private Double calculateEstimatedTimeToTargetHours(int currentRsoc, int targetRsoc, Double signedSocRatePerHour) {
        if (currentRsoc >= targetRsoc) return null;
        if (signedSocRatePerHour == null || Math.abs(signedSocRatePerHour) < MIN_EFFECTIVE_RATE_PER_HOUR) {
            return null;
        }

        double deltaPercent = targetRsoc - currentRsoc;
        if ((deltaPercent > 0 && signedSocRatePerHour <= 0) || (deltaPercent < 0 && signedSocRatePerHour >= 0)) {
            return null;
        }

        double etaHours = Math.abs(deltaPercent / signedSocRatePerHour);
        return Double.isFinite(etaHours) ? etaHours : null;
    }

    private TrendDirection classifyTrend(BatteryStatusResponse status) {
        if (status == null) return TrendDirection.IDLE;
        if (status.isBatteryCharging()) return TrendDirection.CHARGING;
        if (status.isBatteryDischarging()) return TrendDirection.DISCHARGING;
        return TrendDirection.IDLE;
    }

    private TrendDirection classifyTrend(BatteryStatusSample sample) {
        if (sample == null) return TrendDirection.IDLE;
        if (sample.batteryCharging()) return TrendDirection.CHARGING;
        if (sample.batteryDischarging()) return TrendDirection.DISCHARGING;
        return TrendDirection.IDLE;
    }
}
