package de.zeus.power.model;

import java.math.BigDecimal;

public class PriceBreakdown {

    private BigDecimal boerseNettoCt;
    private BigDecimal surchargeNettoCt;
    private BigDecimal totalNettoCt;
    private BigDecimal vatRate;
    private BigDecimal totalBruttoCt;

    public BigDecimal getBoerseNettoCt() {
        return boerseNettoCt;
    }

    public void setBoerseNettoCt(BigDecimal boerseNettoCt) {
        this.boerseNettoCt = boerseNettoCt;
    }

    public BigDecimal getSurchargeNettoCt() {
        return surchargeNettoCt;
    }

    public void setSurchargeNettoCt(BigDecimal surchargeNettoCt) {
        this.surchargeNettoCt = surchargeNettoCt;
    }

    public BigDecimal getTotalNettoCt() {
        return totalNettoCt;
    }

    public void setTotalNettoCt(BigDecimal totalNettoCt) {
        this.totalNettoCt = totalNettoCt;
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public void setVatRate(BigDecimal vatRate) {
        this.vatRate = vatRate;
    }

    public BigDecimal getTotalBruttoCt() {
        return totalBruttoCt;
    }

    public void setTotalBruttoCt(BigDecimal totalBruttoCt) {
        this.totalBruttoCt = totalBruttoCt;
    }
}
