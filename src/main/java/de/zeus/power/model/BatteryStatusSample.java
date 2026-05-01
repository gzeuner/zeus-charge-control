package de.zeus.power.model;

/**
 * Snapshot of battery status values used for UI metric calculations.
 */
public record BatteryStatusSample(
        long timestamp,
        int rsoc,
        Integer remainingCapacityWh,
        int pacTotalW,
        boolean batteryCharging,
        boolean batteryDischarging
) {
}
