package de.zeus.power.model;

import java.util.List;

public class TibberResponse {

    private Data data;

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

    public static class Data {
        private Viewer viewer;

        public Viewer getViewer() {
            return viewer;
        }

        public void setViewer(Viewer viewer) {
            this.viewer = viewer;
        }
    }

    public static class Viewer {
        private List<Home> homes;

        public List<Home> getHomes() {
            return homes;
        }

        public void setHomes(List<Home> homes) {
            this.homes = homes;
        }
    }

    public static class Home {
        private CurrentSubscription currentSubscription;

        public CurrentSubscription getCurrentSubscription() {
            return currentSubscription;
        }

        public void setCurrentSubscription(CurrentSubscription currentSubscription) {
            this.currentSubscription = currentSubscription;
        }
    }

    public static class CurrentSubscription {
        private PriceInfo priceInfo;

        public PriceInfo getPriceInfo() {
            return priceInfo;
        }

        public void setPriceInfo(PriceInfo priceInfo) {
            this.priceInfo = priceInfo;
        }
    }

    public static class PriceInfo {
        private List<PriceData> today;
        private List<PriceData> tomorrow;

        public List<PriceData> getToday() {
            return today;
        }

        public void setToday(List<PriceData> today) {
            this.today = today;
        }

        public List<PriceData> getTomorrow() {
            return tomorrow;
        }

        public void setTomorrow(List<PriceData> tomorrow) {
            this.tomorrow = tomorrow;
        }
    }

    public static class PriceData {
        private double energy;
        private String startsAt;

        public double getEnergy() {
            return energy;
        }

        public void setEnergy(double energy) {
            this.energy = energy;
        }

        public String getStartsAt() {
            return startsAt;
        }

        public void setStartsAt(String startsAt) {
            this.startsAt = startsAt;
        }
    }
}
