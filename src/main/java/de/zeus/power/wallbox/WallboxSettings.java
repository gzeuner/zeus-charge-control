package de.zeus.power.wallbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("wallbox")
record WallboxSettings(@DefaultValue("wallbox.local") String host, @DefaultValue("502") int port,
                       @DefaultValue("1") int unitId, @DefaultValue("ECO") Mode mode,
                       @DefaultValue("8000") int pollIntervalMs, @DefaultValue("20") int smartMinUsoc,
                       @DefaultValue("5") int smartReservePercent, @DefaultValue("16") int resumeHoldSeconds,
                       @DefaultValue("true") boolean consumptionIncludesWallbox,
                       @DefaultValue("2000") int connectTimeoutMs, @DefaultValue("2500") int readTimeoutMs,
                       @DefaultValue("3") int maxErrorsBeforeReconnect) {

    enum Mode { ECO, SMART, POWER }

    WallboxSettings(String host, int port, int unitId, Mode mode, int pollIntervalMs, int smartMinUsoc,
                    int smartReservePercent, int resumeHoldSeconds, boolean consumptionIncludesWallbox) {
        this(host, port, unitId, mode, pollIntervalMs, smartMinUsoc, smartReservePercent, resumeHoldSeconds,
                consumptionIncludesWallbox, 2000, 2500, 3);
    }

    WallboxSettings withPreferences(WallboxPreferences prefs) {
        return new WallboxSettings(host, port, unitId, prefs.mode(), pollIntervalMs, prefs.smartMinUsoc(),
                prefs.smartReservePercent(), resumeHoldSeconds, consumptionIncludesWallbox,
                connectTimeoutMs, readTimeoutMs, maxErrorsBeforeReconnect);
    }

    @ConstructorBinding
    WallboxSettings {
        // Defer value validation to WallboxControl so an enabled but misconfigured device
        // is safely left stopped without preventing the monitoring application from starting.
    }

    String validationError() {
        if (host == null || host.isBlank() || host.trim().matches("<.*>")) return "host is empty or still a placeholder";
        if (port < 1 || port > 65535) return "port must be between 1 and 65535";
        if (unitId < 0 || unitId > 255) return "unit-id must be between 0 and 255";
        if (mode == null || pollIntervalMs <= 0 || pollIntervalMs > 8000
                || smartMinUsoc < 0 || smartMinUsoc > 100 || smartReservePercent < 0
                || smartReservePercent > 100 || resumeHoldSeconds < 0
                || connectTimeoutMs < 1 || connectTimeoutMs > 8000
                || readTimeoutMs < 1 || readTimeoutMs > 3000
                || maxErrorsBeforeReconnect < 1 || maxErrorsBeforeReconnect > 100) {
            return "invalid mode, timing, reserve or reconnect settings";
        }
        return null;
    }
}
