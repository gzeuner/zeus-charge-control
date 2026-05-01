
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
     * Legacy fallback target state of charge (%).
     * Seasonal configuration should be preferred when available.
     */
    private int targetStateOfCharge = 90;

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

    private CapacityProperties capacity = new CapacityProperties();
    private SeasonProperties season = new SeasonProperties();

    public int getTargetStateOfCharge() {
        return targetStateOfCharge;
    }

    public void setTargetStateOfCharge(int targetStateOfCharge) {
        this.targetStateOfCharge = targetStateOfCharge;
    }

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

    public SeasonProperties getSeason() {
        return season;
    }

    public void setSeason(SeasonProperties season) {
        this.season = season;
    }

    public CapacityProperties getCapacity() {
        return capacity;
    }

    public void setCapacity(CapacityProperties capacity) {
        this.capacity = capacity;
    }

    public static class CapacityProperties {
        /**
         * Number of physically installed battery modules when the API does not provide a reliable value.
         */
        private int installedModules = 2;

        /**
         * Usable capacity per installed module in Wh used for UI and derived energy calculations.
         */
        private int moduleUsableCapacityWh = 5000;

        /**
         * Optional API configuration key that exposes the installed module count.
         * Empty by default because the battery API does not document a stable key.
         */
        private String apiInstalledModulesConfigKey = "";

        public int getInstalledModules() {
            return installedModules;
        }

        public void setInstalledModules(int installedModules) {
            this.installedModules = installedModules;
        }

        public int getModuleUsableCapacityWh() {
            return moduleUsableCapacityWh;
        }

        public void setModuleUsableCapacityWh(int moduleUsableCapacityWh) {
            this.moduleUsableCapacityWh = moduleUsableCapacityWh;
        }

        public String getApiInstalledModulesConfigKey() {
            return apiInstalledModulesConfigKey;
        }

        public void setApiInstalledModulesConfigKey(String apiInstalledModulesConfigKey) {
            this.apiInstalledModulesConfigKey = apiInstalledModulesConfigKey;
        }
    }

    public static class SeasonProperties {
        private SeasonTargetProperties winter = new SeasonTargetProperties(11, 2, 90);
        private SeasonTargetProperties summer = new SeasonTargetProperties(3, 10, 70);

        public SeasonTargetProperties getWinter() {
            return winter;
        }

        public void setWinter(SeasonTargetProperties winter) {
            this.winter = winter;
        }

        public SeasonTargetProperties getSummer() {
            return summer;
        }

        public void setSummer(SeasonTargetProperties summer) {
            this.summer = summer;
        }
    }

    public static class SeasonTargetProperties {
        private int startMonth;
        private int endMonth;
        private int targetStateOfCharge;

        public SeasonTargetProperties() {
        }

        public SeasonTargetProperties(int startMonth, int endMonth, int targetStateOfCharge) {
            this.startMonth = startMonth;
            this.endMonth = endMonth;
            this.targetStateOfCharge = targetStateOfCharge;
        }

        public int getStartMonth() {
            return startMonth;
        }

        public void setStartMonth(int startMonth) {
            this.startMonth = startMonth;
        }

        public int getEndMonth() {
            return endMonth;
        }

        public void setEndMonth(int endMonth) {
            this.endMonth = endMonth;
        }

        public int getTargetStateOfCharge() {
            return targetStateOfCharge;
        }

        public void setTargetStateOfCharge(int targetStateOfCharge) {
            this.targetStateOfCharge = targetStateOfCharge;
        }
    }
}
