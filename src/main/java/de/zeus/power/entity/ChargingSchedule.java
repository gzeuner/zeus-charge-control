package de.zeus.power.entity;

import javax.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

@Entity
@Table(name = "charging_schedule", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"startTimestamp", "endTimestamp", "price"})
})
public class ChargingSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private Long startTimestamp;
    private Long endTimestamp;
    private Double price;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStartTimestamp() {
        return startTimestamp;
    }

    public void setStartTimestamp(Long startTimestamp) {
        this.startTimestamp = startTimestamp;
    }

    public Long getEndTimestamp() {
        return endTimestamp;
    }

    public void setEndTimestamp(Long endTimestamp) {
        this.endTimestamp = endTimestamp;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public String getFormattedStartTimestamp() {
        return formatTimestamp(startTimestamp);
    }

    public String getFormattedEndTimestamp() {
        return formatTimestamp(endTimestamp);
    }

    private String formatTimestamp(Long timestamp) {
        if (timestamp == null) return "N/A";

        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
        return dateTime.format(formatter);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChargingSchedule that = (ChargingSchedule) o;
        return Objects.equals(startTimestamp, that.startTimestamp) && Objects.equals(endTimestamp, that.endTimestamp) && Objects.equals(price, that.price);
    }

    @Override
    public int hashCode() {
        return Objects.hash(startTimestamp, endTimestamp, price);
    }
}
