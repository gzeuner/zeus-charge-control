package de.zeus.power.wallbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("wallbox")
record WallboxSettings(@DefaultValue("wallbox.local") String host, @DefaultValue("502") int port,
                       @DefaultValue("1") int unitId, @DefaultValue("ECO") Mode mode,
                       @DefaultValue("8000") int pollIntervalMs, @DefaultValue("20") int smartMinUsoc,
                       @DefaultValue("5") int smartReservePercent, @DefaultValue("16") int resumeHoldSeconds,
                       @DefaultValue("true") boolean consumptionIncludesWallbox) {

    enum Mode { ECO, SMART, POWER }

    WallboxSettings withPreferences(WallboxPreferences prefs) {
        return new WallboxSettings(host, port, unitId, prefs.mode(), pollIntervalMs, prefs.smartMinUsoc(),
                prefs.smartReservePercent(), resumeHoldSeconds, consumptionIncludesWallbox);
    }

    WallboxSettings {
        if (host == null || host.isBlank() || port < 1 || port > 65535 || unitId < 0 || unitId > 255) {
            throw new IllegalArgumentException("Invalid wallbox host, port or unit ID");
        }
        if (mode == null || pollIntervalMs <= 0 || pollIntervalMs > 8000
                || smartMinUsoc < 0 || smartMinUsoc > 100 || smartReservePercent < 0
                || smartReservePercent > 100 || resumeHoldSeconds < 0) {
            throw new IllegalArgumentException("Invalid wallbox mode, poll interval, reserve or resume hold");
        }
    }
}
