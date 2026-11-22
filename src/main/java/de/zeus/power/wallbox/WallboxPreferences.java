package de.zeus.power.wallbox;

import java.util.Map;

record WallboxPreferences(boolean enabled, WallboxSettings.Mode mode, int smartMinUsoc, int smartReservePercent) {
    WallboxPreferences {
        if (smartMinUsoc < 0 || smartMinUsoc > 100 || smartReservePercent < 0 || smartReservePercent > 100) {
            throw new IllegalArgumentException("Smart limits must be between 0 and 100");
        }
    }

    private static <T> T require(Object value, Class<T> type) {
        if (!type.isInstance(value)) throw new IllegalArgumentException("Invalid wallbox setting value");
        return type.cast(value);
    }

    WallboxPreferences update(Map<String, Object> changes) {
        boolean on = enabled;
        var nextMode = mode;
        int min = smartMinUsoc, reserve = smartReservePercent;
        if (changes.isEmpty()) throw new IllegalArgumentException("Empty update");
        for (var entry : changes.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "enabled" -> on = require(value, Boolean.class);
                case "mode" -> nextMode = WallboxSettings.Mode.valueOf(require(value, String.class));
                case "smartMinUsoc" -> min = require(value, Integer.class);
                case "smartReservePercent" -> reserve = require(value, Integer.class);
                default -> throw new IllegalArgumentException("Invalid wallbox setting: " + entry.getKey());
            }
        }
        return new WallboxPreferences(on, nextMode, min, reserve);
    }
}
