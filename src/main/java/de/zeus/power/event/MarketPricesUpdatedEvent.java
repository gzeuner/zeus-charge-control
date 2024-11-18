package de.zeus.power.event;

public class MarketPricesUpdatedEvent {
    private final Object source;

    public MarketPricesUpdatedEvent(Object source) {
        this.source = source;
    }

    public Object getSource() {
        return source;
    }
}
