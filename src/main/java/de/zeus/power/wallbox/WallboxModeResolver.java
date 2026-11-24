package de.zeus.power.wallbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WallboxModeResolver {
    private static final Logger LOG = LoggerFactory.getLogger(WallboxModeResolver.class);
    private WallboxModeResolver() {}

    record Resolution(WallboxSettings.Mode mode, String source) {}

    static Resolution resolve(WallboxSettings.Mode persistedUiMode, String environmentMode, String propertyMode) {
        if (persistedUiMode != null) return new Resolution(persistedUiMode, "UI");
        if (environmentMode != null && !environmentMode.isBlank()) {
            return new Resolution(parseOrDefault(environmentMode, "WALLBOX_MODE environment variable"), "ENV");
        }
        return new Resolution(parseOrDefault(propertyMode, "wallbox.mode property"), "config");
    }

    private static WallboxSettings.Mode parseOrDefault(String value, String source) {
        WallboxSettings.Mode parsed = WallboxSettings.parseMode(value);
        if (parsed != null) return parsed;
        LOG.warn("Invalid wallbox mode from {}; falling back to POWER", source);
        return WallboxSettings.Mode.POWER;
    }
}
