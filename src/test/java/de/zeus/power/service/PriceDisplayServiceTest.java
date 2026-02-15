package de.zeus.power.service;

import de.zeus.power.config.PriceDisplayProperties;
import de.zeus.power.model.PriceBreakdown;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceDisplayServiceTest {

    @Test
    void calculate_appliesSurchargesAndVat() {
        PriceDisplayProperties props = new PriceDisplayProperties();
        PriceDisplayService service = new PriceDisplayService(props);

        PriceBreakdown breakdown = service.calculate(new BigDecimal("8.60"));

        assertNotNull(breakdown);
        double brutto = breakdown.getTotalBruttoCt().doubleValue();
        assertTrue(Math.abs(brutto - 29.23) <= 0.02, "Expected brutto around 29.23, got " + brutto);
    }
}
