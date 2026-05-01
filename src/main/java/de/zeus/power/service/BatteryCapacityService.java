package de.zeus.power.service;

import de.zeus.power.config.BatteryProperties;
import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryCapacitySnapshot;
import de.zeus.power.model.BatteryStatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the usable battery capacity and the currently available remaining capacity.
 */
@Service
public class BatteryCapacityService {
    private static final Pattern FIRST_INTEGER_PATTERN = Pattern.compile("-?\\d+");

    private final BatteryProperties batteryProperties;
    private final BatteryCommandService batteryCommandService;

    @Value("${battery.config.cache.duration.seconds:300}")
    private int configCacheDurationSeconds;

    private volatile Integer cachedInstalledModulesFromApi;
    private volatile Instant cachedInstalledModulesTimestamp;

    public BatteryCapacityService(BatteryProperties batteryProperties,
                                  BatteryCommandService batteryCommandService) {
        this.batteryProperties = batteryProperties;
        this.batteryCommandService = batteryCommandService;
    }

    public BatteryCapacitySnapshot calculateSnapshot(BatteryStatusResponse batteryStatus) {
        if (batteryStatus == null) {
            return new BatteryCapacitySnapshot(0, 0, resolveTotalCapacityWh(), resolveInstalledModules(),
                    resolveModuleUsableCapacityWh(), null);
        }

        int stateOfChargePercent = clampPercent(batteryStatus.getRsoc());
        int totalCapacityWh = resolveTotalCapacityWh();
        Integer rawRemainingCapacityWh = batteryStatus.getRemainingCapacityWh() >= 0
                ? batteryStatus.getRemainingCapacityWh()
                : null;
        int remainingCapacityWh = totalCapacityWh > 0
                ? (int) Math.round(totalCapacityWh * (stateOfChargePercent / 100.0))
                : Math.max(0, rawRemainingCapacityWh != null ? rawRemainingCapacityWh : 0);

        return new BatteryCapacitySnapshot(
                stateOfChargePercent,
                remainingCapacityWh,
                totalCapacityWh,
                resolveInstalledModules(),
                resolveModuleUsableCapacityWh(),
                rawRemainingCapacityWh
        );
    }

    public int resolveTotalCapacityWh() {
        return Math.max(0, resolveInstalledModules() * resolveModuleUsableCapacityWh());
    }

    public int resolveInstalledModules() {
        Integer detectedInstalledModules = readInstalledModulesFromApi();
        if (detectedInstalledModules != null && detectedInstalledModules > 0) {
            return detectedInstalledModules;
        }
        return Math.max(1, batteryProperties.getCapacity().getInstalledModules());
    }

    public int resolveModuleUsableCapacityWh() {
        return Math.max(1, batteryProperties.getCapacity().getModuleUsableCapacityWh());
    }

    private Integer readInstalledModulesFromApi() {
        String configKey = trimToNull(batteryProperties.getCapacity().getApiInstalledModulesConfigKey());
        if (configKey == null || batteryCommandService.isBatteryNotConfigured()) {
            return null;
        }

        if (cachedInstalledModulesFromApi != null && cachedInstalledModulesTimestamp != null) {
            long ageSeconds = Duration.between(cachedInstalledModulesTimestamp, Instant.now()).getSeconds();
            if (ageSeconds <= Math.max(0, configCacheDurationSeconds)) {
                return cachedInstalledModulesFromApi;
            }
        }

        ApiResponse<String> response = batteryCommandService.getConfiguration(configKey);
        if (response == null || !response.success() || response.data() == null) {
            return null;
        }

        Integer parsed = parseFirstInteger(response.data());
        if (parsed != null && parsed > 0) {
            cachedInstalledModulesFromApi = parsed;
            cachedInstalledModulesTimestamp = Instant.now();
            return parsed;
        }

        return null;
    }

    private static Integer parseFirstInteger(String value) {
        if (value == null || value.isBlank()) return null;
        Matcher matcher = FIRST_INTEGER_PATTERN.matcher(value);
        if (!matcher.find()) return null;
        try {
            return Integer.parseInt(matcher.group());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
