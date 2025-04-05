package de.zeus.power.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Copyright 2024 Guido Zeuner - https://tiny-tool.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class BatteryStatusResponse {

    @JsonProperty("Apparent_output")
    private int apparentOutput;

    @JsonProperty("BackupBuffer")
    private String backupBuffer;

    @JsonProperty("BatteryCharging")
    private boolean batteryCharging;

    @JsonProperty("BatteryDischarging")
    private boolean batteryDischarging;

    @JsonProperty("Consumption_W")
    private int consumptionW;

    @JsonProperty("Fac")
    private double fac;

    @JsonProperty("FlowConsumptionBattery")
    private boolean flowConsumptionBattery;

    @JsonProperty("FlowConsumptionGrid")
    private boolean flowConsumptionGrid;

    @JsonProperty("FlowConsumptionProduction")
    private boolean flowConsumptionProduction;

    @JsonProperty("FlowGridBattery")
    private boolean flowGridBattery;

    @JsonProperty("FlowProductionBattery")
    private boolean flowProductionBattery;

    @JsonProperty("FlowProductionGrid")
    private boolean flowProductionGrid;

    @JsonProperty("GridFeedIn_W")
    private int gridFeedInW;

    @JsonProperty("IsSystemInstalled")
    private int isSystemInstalled;

    @JsonProperty("OperatingMode")
    private String operatingMode;

    @JsonProperty("Pac_total_W")
    private int pacTotalW;

    @JsonProperty("Production_W")
    private int productionW;

    @JsonProperty("RSOC")
    private int rsoc;

    @JsonProperty("RemainingCapacity_Wh")
    private int remainingCapacityWh;

    @JsonProperty("Sac1")
    private int sac1;

    @JsonProperty("Sac2")
    private Integer sac2;

    @JsonProperty("Sac3")
    private Integer sac3;

    @JsonProperty("SystemStatus")
    private String systemStatus;

    @JsonProperty("Timestamp")
    private String timestamp;

    @JsonProperty("USOC")
    private int usoc;

    @JsonProperty("Uac")
    private int uac;

    @JsonProperty("Ubat")
    private int ubat;

    @JsonProperty("dischargeNotAllowed")
    private boolean dischargeNotAllowed;

    @JsonProperty("generator_autostart")
    private boolean generatorAutostart;

    public int getApparentOutput() {
        return apparentOutput;
    }

    public void setApparentOutput(int apparentOutput) {
        this.apparentOutput = apparentOutput;
    }

    public String getBackupBuffer() {
        return backupBuffer;
    }

    public void setBackupBuffer(String backupBuffer) {
        this.backupBuffer = backupBuffer;
    }

    public boolean isBatteryCharging() {
        return batteryCharging;
    }

    public void setBatteryCharging(boolean batteryCharging) {
        this.batteryCharging = batteryCharging;
    }

    public boolean isBatteryDischarging() {
        return batteryDischarging;
    }

    public void setBatteryDischarging(boolean batteryDischarging) {
        this.batteryDischarging = batteryDischarging;
    }

    public int getConsumptionW() {
        return consumptionW;
    }

    public void setConsumptionW(int consumptionW) {
        this.consumptionW = consumptionW;
    }

    public double getFac() {
        return fac;
    }

    public void setFac(double fac) {
        this.fac = fac;
    }

    public boolean isFlowConsumptionBattery() {
        return flowConsumptionBattery;
    }

    public void setFlowConsumptionBattery(boolean flowConsumptionBattery) {
        this.flowConsumptionBattery = flowConsumptionBattery;
    }

    public boolean isFlowConsumptionGrid() {
        return flowConsumptionGrid;
    }

    public void setFlowConsumptionGrid(boolean flowConsumptionGrid) {
        this.flowConsumptionGrid = flowConsumptionGrid;
    }

    public boolean isFlowConsumptionProduction() {
        return flowConsumptionProduction;
    }

    public void setFlowConsumptionProduction(boolean flowConsumptionProduction) {
        this.flowConsumptionProduction = flowConsumptionProduction;
    }

    public boolean isFlowGridBattery() {
        return flowGridBattery;
    }

    public void setFlowGridBattery(boolean flowGridBattery) {
        this.flowGridBattery = flowGridBattery;
    }

    public boolean isFlowProductionBattery() {
        return flowProductionBattery;
    }

    public void setFlowProductionBattery(boolean flowProductionBattery) {
        this.flowProductionBattery = flowProductionBattery;
    }

    public boolean isFlowProductionGrid() {
        return flowProductionGrid;
    }

    public void setFlowProductionGrid(boolean flowProductionGrid) {
        this.flowProductionGrid = flowProductionGrid;
    }

    public int getGridFeedInW() {
        return gridFeedInW;
    }

    public void setGridFeedInW(int gridFeedInW) {
        this.gridFeedInW = gridFeedInW;
    }

    public int getIsSystemInstalled() {
        return isSystemInstalled;
    }

    public void setIsSystemInstalled(int isSystemInstalled) {
        this.isSystemInstalled = isSystemInstalled;
    }

    public String getOperatingMode() {
        return operatingMode;
    }

    public void setOperatingMode(String operatingMode) {
        this.operatingMode = operatingMode;
    }

    public int getPacTotalW() {
        return pacTotalW;
    }

    public void setPacTotalW(int pacTotalW) {
        this.pacTotalW = pacTotalW;
    }

    public int getProductionW() {
        return productionW;
    }

    public void setProductionW(int productionW) {
        this.productionW = productionW;
    }

    public int getRsoc() {
        return rsoc;
    }

    public void setRsoc(int rsoc) {
        this.rsoc = rsoc;
    }

    public int getRemainingCapacityWh() {
        return remainingCapacityWh;
    }

    public void setRemainingCapacityWh(int remainingCapacityWh) {
        this.remainingCapacityWh = remainingCapacityWh;
    }

    public int getSac1() {
        return sac1;
    }

    public void setSac1(int sac1) {
        this.sac1 = sac1;
    }

    public Integer getSac2() {
        return sac2;
    }

    public void setSac2(Integer sac2) {
        this.sac2 = sac2;
    }

    public Integer getSac3() {
        return sac3;
    }

    public void setSac3(Integer sac3) {
        this.sac3 = sac3;
    }

    public String getSystemStatus() {
        return systemStatus;
    }

    public void setSystemStatus(String systemStatus) {
        this.systemStatus = systemStatus;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public int getUsoc() {
        return usoc;
    }

    public void setUsoc(int usoc) {
        this.usoc = usoc;
    }

    public int getUac() {
        return uac;
    }

    public void setUac(int uac) {
        this.uac = uac;
    }

    public int getUbat() {
        return ubat;
    }

    public void setUbat(int ubat) {
        this.ubat = ubat;
    }

    public boolean isDischargeNotAllowed() {
        return dischargeNotAllowed;
    }

    public void setDischargeNotAllowed(boolean dischargeNotAllowed) {
        this.dischargeNotAllowed = dischargeNotAllowed;
    }

    public boolean isGeneratorAutostart() {
        return generatorAutostart;
    }

    public void setGeneratorAutostart(boolean generatorAutostart) {
        this.generatorAutostart = generatorAutostart;
    }

    @Override
    public String toString() {
        return "BatteryStatus{" +
                "apparentOutput=" + apparentOutput +
                ", backupBuffer='" + backupBuffer + '\'' +
                ", batteryCharging=" + batteryCharging +
                ", batteryDischarging=" + batteryDischarging +
                ", consumptionW=" + consumptionW +
                ", fac=" + fac +
                ", flowConsumptionBattery=" + flowConsumptionBattery +
                ", flowConsumptionGrid=" + flowConsumptionGrid +
                ", flowConsumptionProduction=" + flowConsumptionProduction +
                ", flowGridBattery=" + flowGridBattery +
                ", flowProductionBattery=" + flowProductionBattery +
                ", flowProductionGrid=" + flowProductionGrid +
                ", gridFeedInW=" + gridFeedInW +
                ", isSystemInstalled=" + isSystemInstalled +
                ", operatingMode='" + operatingMode + '\'' +
                ", pacTotalW=" + pacTotalW +
                ", productionW=" + productionW +
                ", rsoc=" + rsoc +
                ", remainingCapacityWh=" + remainingCapacityWh +
                ", sac1=" + sac1 +
                ", sac2=" + sac2 +
                ", sac3=" + sac3 +
                ", systemStatus='" + systemStatus + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", usoc=" + usoc +
                ", uac=" + uac +
                ", ubat=" + ubat +
                ", dischargeNotAllowed=" + dischargeNotAllowed +
                ", generatorAutostart=" + generatorAutostart +
                '}';
    }
}
