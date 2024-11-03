package de.zeus.power.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class MarketPriceResponse {

    private String object;

    private List<MarketData> data;

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public List<MarketData> getData() {
        return data;
    }

    public void setData(List<MarketData> data) {
        this.data = data;
    }

    public static class MarketData {

        @JsonProperty("start_timestamp")
        private long startTimestamp;

        @JsonProperty("end_timestamp")
        private long endTimestamp;

        private double marketprice;

        private String unit;

        // Standard-Getter und -Setter

        public long getStartTimestamp() {
            return startTimestamp;
        }

        public void setStartTimestamp(long startTimestamp) {
            this.startTimestamp = startTimestamp;
        }

        public long getEndTimestamp() {
            return endTimestamp;
        }

        public void setEndTimestamp(long endTimestamp) {
            this.endTimestamp = endTimestamp;
        }

        public double getMarketprice() {
            return marketprice;
        }

        public void setMarketprice(double marketprice) {
            this.marketprice = marketprice;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }
    }
}
