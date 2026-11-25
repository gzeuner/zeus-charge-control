package de.zeus.power.wallbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = WallboxSettingsBindingTest.TestConfig.class)
class WallboxSettingsBindingTest {
    @Autowired
    private WallboxSettings settings;

    @Test
    void bindsNeutralSmartThresholdDefaults() {
        assertEquals(30, settings.smartMinUsoc());
        assertEquals(10, settings.smartReservePercent());
    }

    @SpringBootConfiguration
    @TestComponent
    @EnableConfigurationProperties(WallboxSettings.class)
    static class TestConfig {
    }
}
