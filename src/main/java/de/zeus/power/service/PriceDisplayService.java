package de.zeus.power.service;

import de.zeus.power.config.PriceDisplayProperties;
import de.zeus.power.model.PriceBreakdown;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
public class PriceDisplayService {

    private static final int SCALE = 2;
    private final PriceDisplayProperties properties;

    public PriceDisplayService(PriceDisplayProperties properties) {
        this.properties = properties;
    }

    public PriceBreakdown calculate(BigDecimal boerseNettoCt) {
        if (boerseNettoCt == null) {
            return null;
        }

        BigDecimal surchargeNetto = sumSurcharges();
        BigDecimal totalNetto = boerseNettoCt.add(surchargeNetto);
        BigDecimal vatRate = properties.getVatRate() != null ? properties.getVatRate() : BigDecimal.ZERO;
        BigDecimal totalBrutto = totalNetto.multiply(BigDecimal.ONE.add(vatRate));

        PriceBreakdown breakdown = new PriceBreakdown();
        breakdown.setBoerseNettoCt(scale(boerseNettoCt));
        breakdown.setSurchargeNettoCt(scale(surchargeNetto));
        breakdown.setTotalNettoCt(scale(totalNetto));
        breakdown.setVatRate(vatRate);
        breakdown.setTotalBruttoCt(scale(totalBrutto));
        return breakdown;
    }

    private BigDecimal sumSurcharges() {
        PriceDisplayProperties.Surcharges s = properties.getSurcharges();
        return safe(s.getProcurement())
                .add(safe(s.getGrid()))
                .add(safe(s.getConcession()))
                .add(safe(s.getElectricityTax()))
                .add(safe(s.getOffshore()))
                .add(safe(s.getKwk()))
                .add(safe(s.getStromnev19()));
    }

    private BigDecimal safe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
