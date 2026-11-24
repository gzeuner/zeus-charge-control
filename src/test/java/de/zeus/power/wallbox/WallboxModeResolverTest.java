package de.zeus.power.wallbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WallboxModeResolverTest {
    @Test
    void persistedUiModeOverridesEnvironmentAndProperty() {
        var selected = WallboxModeResolver.resolve(WallboxSettings.Mode.SMART, "ECO", "POWER");
        assertEquals(WallboxSettings.Mode.SMART, selected.mode());
        assertEquals("UI", selected.source());
    }

    @Test
    void environmentOverridesPropertyAndPropertyDefaultIsPower() {
        var fromEnvironment = WallboxModeResolver.resolve(null, "power", "ECO");
        assertEquals(WallboxSettings.Mode.POWER, fromEnvironment.mode());
        assertEquals("ENV", fromEnvironment.source());

        var fromDefault = WallboxModeResolver.resolve(null, null, null);
        assertEquals(WallboxSettings.Mode.POWER, fromDefault.mode());
        assertEquals("config", fromDefault.source());
    }

    @Test
    void invalidModeFallsBackToPowerWithoutThrowing() {
        assertNull(WallboxSettings.parseMode("FAST"));
        assertEquals(WallboxSettings.Mode.POWER, new WallboxSettings("localhost", 502, 1,
                "FAST", 8000, 20, 5, 0, true).effectiveMode());
        assertEquals(WallboxSettings.Mode.POWER,
                WallboxModeResolver.resolve(null, "FAST", "ECO").mode());
        assertEquals(WallboxSettings.Mode.POWER,
                new WallboxPreferences(true, WallboxSettings.Mode.ECO, 20, 5)
                        .update(java.util.Map.of("mode", "FAST")).mode());
    }

    @Test
    void configSavePreservesPlaceholdersAndUiModeSurvivesRestart(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("application.properties");
        String placeholder = "wallbox.mode=${WALLBOX_MODE:ECO}\r\nwallbox.config-file=${WALLBOX_CONFIG_FILE:./config/application.properties}\r\n";
        Files.writeString(file, placeholder);
        var config = new WallboxConfigFile(file);
        config.save(new WallboxPreferences(true, WallboxSettings.Mode.POWER, 20, 5));

        String written = Files.readString(file);
        assertTrue(written.contains("wallbox.mode=${WALLBOX_MODE:ECO}"));
        assertTrue(written.contains("wallbox.config-file=${WALLBOX_CONFIG_FILE:./config/application.properties}"));
        var reloaded = config.read(new WallboxPreferences(true, WallboxSettings.Mode.ECO, 20, 5));
        assertTrue(reloaded.hasGuiBlock());
        assertEquals(WallboxSettings.Mode.POWER, reloaded.persistedMode());
        assertEquals(WallboxSettings.Mode.POWER, reloaded.preferences().mode());
    }
}
