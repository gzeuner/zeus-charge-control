package de.zeus.power.wallbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record SonnenReadings(@JsonProperty("Production_W") Double productionW,
                      @JsonProperty("Consumption_W") Double consumptionW,
                      @JsonProperty("GridFeedIn_W") Double gridFeedInW,
                      @JsonProperty("Pac_total_W") Double batteryW,
                      @JsonProperty("USOC") Double usoc,
                      @JsonProperty("BackupBuffer") Double backupBuffer) {

    boolean hasPowerReadings() {
        return productionW != null && Double.isFinite(productionW) && productionW >= 0
                && consumptionW != null && Double.isFinite(consumptionW) && consumptionW >= 0
                && batteryW != null && Double.isFinite(batteryW);
    }

    boolean aboveReserve(WallboxSettings settings) {
        return usoc != null && usoc <= 100 && backupBuffer != null && backupBuffer >= 0
                && usoc > Math.max(settings.smartMinUsoc(), backupBuffer + settings.smartReservePercent());
    }
}
