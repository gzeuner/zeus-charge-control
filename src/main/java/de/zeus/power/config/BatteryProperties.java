
package de.zeus.power.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Battery-related tunables.
 * If you already have this class, just add the missing fields + getters.
 */
@Component
@ConfigurationProperties(prefix = "battery")
public class BatteryProperties {

    /**
     * Max import power from grid used by optimization (W).
     * Example: 4600
     */
    private int gridImportLimitWatts = 4600;

    /**
     * Optional inverter maximum (W). 0 = read from device/status if supported by your services.
     */
    private int inverterMaxWatts = 0;

    /**
     * Minimal "pause" setpoint to keep EMS/Setpoint active when not charging (W).
     * We validated 1 W as accepted by the API.
     */
    private int nightPauseWatts = 1;

    /**
     * Interval (seconds) to refresh setpoint during active windows.
     * Matches the behavior we validated with the HOLD test.
     */
    private int holdIntervalSeconds = 15;

    public int getGridImportLimitWatts() {
        return gridImportLimitWatts;
    }

    public void setGridImportLimitWatts(int gridImportLimitWatts) {
        this.gridImportLimitWatts = gridImportLimitWatts;
    }

    public int getInverterMaxWatts() {
        return inverterMaxWatts;
    }

    public void setInverterMaxWatts(int inverterMaxWatts) {
        this.inverterMaxWatts = inverterMaxWatts;
    }

    public int getNightPauseWatts() {
        return nightPauseWatts;
    }

    public void setNightPauseWatts(int nightPauseWatts) {
        this.nightPauseWatts = nightPauseWatts;
    }

    public int getHoldIntervalSeconds() {
        return holdIntervalSeconds;
    }

    public void setHoldIntervalSeconds(int holdIntervalSeconds) {
        this.holdIntervalSeconds = holdIntervalSeconds;
    }
}
