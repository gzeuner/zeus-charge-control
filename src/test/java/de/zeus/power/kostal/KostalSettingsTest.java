package de.zeus.power.kostal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

class KostalSettingsTest {
    @Test void missingPropertyDefaultsKeepBothDeviceFeaturesDisabled() {
        KostalSettings inverter = new KostalSettings(false, "", 1502, 71, "CDAB", 5000,
                2000, 3000, false, false, true, "SONNEN", 30000);
        assertFalse(inverter.enabled());
        assertEquals("host is empty or still a placeholder", inverter.validationError());
    }

    @Test void missingSpringPropertiesDefaultInverterToDisabled() {
        new ApplicationContextRunner().withUserConfiguration(SettingsConfig.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(KostalSettings.class).enabled());
            assertEquals("SONNEN", context.getBean(KostalSettings.class).pvSource());
        });
    }

    @Test void placeholderHostAndInvalidSettingsAreRejectedWithoutThrowing() {
        assertNotNull(settings("<INVERTER_IP>", 1502, 71, "CDAB", "SONNEN").validationError());
        assertTrue(settings("inverter", 0, 71, "CDAB", "SONNEN").validationError().contains("port"));
        assertTrue(settings("inverter", 1502, 256, "CDAB", "SONNEN").validationError().contains("unit-id"));
        assertTrue(settings("inverter", 1502, 71, "XYZ", "SONNEN").validationError().contains("byte-order"));
        assertTrue(settings("inverter", 1502, 71, "CDAB", "OTHER").validationError().contains("pv-source"));
    }

    @Test void bothRegisterByteOrdersDecodeKnownVector() {
        int bits = Float.floatToIntBits(4962.6f);
        int high = bits >>> 16, low = bits & 0xffff;
        assertEquals(4962.6f, KostalInverterService.decodeFloat(low, high, "CDAB"), 0.01f);
        assertEquals(4962.6f, KostalInverterService.decodeFloat(high, low, "ABCD"), 0.01f);
        assertEquals(4814f, decode(4814f, "CDAB"), 0.01f);
        assertEquals(0f, decode(0f, "ABCD"));
        assertTrue(Float.isNaN(decode(Float.NaN, "CDAB")));
        assertEquals(-12.5f, decode(-12.5f, "ABCD"));
    }

    @Test void pvPlausibilityRejectsInvalidPowerAndClampsSmallNegativeValues() throws Exception {
        assertEquals(0, KostalInverterService.validatePvPower(-20, 30000));
        assertEquals(4962.6, KostalInverterService.validatePvPower(4962.6, 30000), 0.01);
        assertThrows(java.io.IOException.class, () -> KostalInverterService.validatePvPower(Double.NaN, 30000));
        assertThrows(java.io.IOException.class, () -> KostalInverterService.validatePvPower(Double.POSITIVE_INFINITY, 30000));
        assertThrows(java.io.IOException.class, () -> KostalInverterService.validatePvPower(-50.1, 30000));
        assertThrows(java.io.IOException.class, () -> KostalInverterService.validatePvPower(30000.1, 30000));
    }

    @Test void reconnectBackoffCapsAtThirtySeconds() {
        assertEquals(2000, KostalInverterService.backoffDelayMillis(0));
        assertEquals(5000, KostalInverterService.backoffDelayMillis(1));
        assertEquals(10000, KostalInverterService.backoffDelayMillis(2));
        assertEquals(30000, KostalInverterService.backoffDelayMillis(3));
        assertEquals(30000, KostalInverterService.backoffDelayMillis(20));
    }

    private float decode(float value, String order) {
        int bits = Float.floatToIntBits(value);
        int high = bits >>> 16, low = bits & 0xffff;
        return "CDAB".equals(order)
                ? KostalInverterService.decodeFloat(low, high, order)
                : KostalInverterService.decodeFloat(high, low, order);
    }

    private KostalSettings settings(String host, int port, int unit, String order, String source) {
        return new KostalSettings(true, host, port, unit, order, 5000, 2000, 3000,
                false, false, true, source, 30000);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(KostalSettings.class)
    static class SettingsConfig { }
}
