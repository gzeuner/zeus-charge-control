package de.zeus.power.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "price.display")
public class PriceDisplayProperties {

    private BigDecimal vatRate = new BigDecimal("0.19");
    private final Surcharges surcharges = new Surcharges();

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public void setVatRate(BigDecimal vatRate) {
        this.vatRate = vatRate;
    }

    public Surcharges getSurcharges() {
        return surcharges;
    }

    public static class Surcharges {
        private BigDecimal procurement = new BigDecimal("1.81");
        private BigDecimal grid = new BigDecimal("7.58");
        private BigDecimal concession = new BigDecimal("1.59");
        private BigDecimal electricityTax = new BigDecimal("2.05");
        private BigDecimal offshore = new BigDecimal("0.941");
        private BigDecimal kwk = new BigDecimal("0.446");
        private BigDecimal stromnev19 = new BigDecimal("1.56");

        public BigDecimal getProcurement() {
            return procurement;
        }

        public void setProcurement(BigDecimal procurement) {
            this.procurement = procurement;
        }

        public BigDecimal getGrid() {
            return grid;
        }

        public void setGrid(BigDecimal grid) {
            this.grid = grid;
        }

        public BigDecimal getConcession() {
            return concession;
        }

        public void setConcession(BigDecimal concession) {
            this.concession = concession;
        }

        public BigDecimal getElectricityTax() {
            return electricityTax;
        }

        public void setElectricityTax(BigDecimal electricityTax) {
            this.electricityTax = electricityTax;
        }

        public BigDecimal getOffshore() {
            return offshore;
        }

        public void setOffshore(BigDecimal offshore) {
            this.offshore = offshore;
        }

        public BigDecimal getKwk() {
            return kwk;
        }

        public void setKwk(BigDecimal kwk) {
            this.kwk = kwk;
        }

        public BigDecimal getStromnev19() {
            return stromnev19;
        }

        public void setStromnev19(BigDecimal stromnev19) {
            this.stromnev19 = stromnev19;
        }
    }
}
