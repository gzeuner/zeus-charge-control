package de.zeus.power.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        classes = BatteryPropertiesBindingTest.TestConfig.class,
        properties = {
                "battery.season.winter.start-month=11",
                "battery.season.winter.end-month=2",
                "battery.season.winter.target-state-of-charge=90",
                "battery.season.summer.start-month=3",
                "battery.season.summer.end-month=10",
                "battery.season.summer.target-state-of-charge=70",
                "battery.capacity.installed-modules=2",
                "battery.capacity.module-usable-capacity-wh=5000",
                "battery.capacity.api-installed-modules-config-key=BatteryModules"
        }
)
class BatteryPropertiesBindingTest {

    @Autowired
    private BatteryProperties batteryProperties;

    @Test
    void bindsSeasonalMonthsAndTargets() {
        assertEquals(11, batteryProperties.getSeason().getWinter().getStartMonth());
        assertEquals(2, batteryProperties.getSeason().getWinter().getEndMonth());
        assertEquals(90, batteryProperties.getSeason().getWinter().getTargetStateOfCharge());
        assertEquals(3, batteryProperties.getSeason().getSummer().getStartMonth());
        assertEquals(10, batteryProperties.getSeason().getSummer().getEndMonth());
        assertEquals(70, batteryProperties.getSeason().getSummer().getTargetStateOfCharge());
        assertEquals(2, batteryProperties.getCapacity().getInstalledModules());
        assertEquals(5000, batteryProperties.getCapacity().getModuleUsableCapacityWh());
        assertEquals("BatteryModules", batteryProperties.getCapacity().getApiInstalledModulesConfigKey());
    }

    @SpringBootConfiguration
    @EnableConfigurationProperties(BatteryProperties.class)
    static class TestConfig {
    }
}
