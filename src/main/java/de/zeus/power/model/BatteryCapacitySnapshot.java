package de.zeus.power.model;

/**
 * Resolved battery capacity values for display and derived calculations.
 */
public record BatteryCapacitySnapshot(
        int stateOfChargePercent,
        int remainingCapacityWh,
        int totalCapacityWh,
        int installedModules,
        int moduleUsableCapacityWh,
        Integer rawRemainingCapacityWh
) {
}
