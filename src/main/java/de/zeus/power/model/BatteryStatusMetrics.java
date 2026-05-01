package de.zeus.power.model;

/**
 * Derived battery metrics rendered in the status UI.
 */
public record BatteryStatusMetrics(
        Double dropRatePerHour,
        Double estimatedTimeToTargetHours
) {
}
