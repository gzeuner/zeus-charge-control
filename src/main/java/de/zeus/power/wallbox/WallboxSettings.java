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
        if (host == null || host.isBlank() || port < 1 || port > 65535 || unitId < 0 || unitId > 255) {
            throw new IllegalArgumentException("Invalid wallbox host, port or unit ID");
        }
        if (mode == null || pollIntervalMs <= 0 || pollIntervalMs > 8000
                || smartMinUsoc < 0 || smartMinUsoc > 100 || smartReservePercent < 0
                || smartReservePercent > 100 || resumeHoldSeconds < 0
                || connectTimeoutMs < 1 || connectTimeoutMs > 8000
                || readTimeoutMs < 1 || readTimeoutMs > 3000
                || maxErrorsBeforeReconnect < 1 || maxErrorsBeforeReconnect > 100) {
            throw new IllegalArgumentException("Invalid wallbox mode, timing, reserve or reconnect settings");
        }
    }
}
