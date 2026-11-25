package de.zeus.power.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import de.zeus.power.config.LogFilter;
import de.zeus.power.util.ChargingUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Simple Open-Meteo client to fetch hourly cloud cover and decide whether
 * it's worth waiting for sunshine (PV) instead of grid-charging.
 */
@Service
public class WeatherForecastService {

    @Value("${weather.api.enabled:false}")
    private boolean enabled;

    @Value("${weather.api.base-url:https://api.open-meteo.com/v1/forecast}")
    private String baseUrl;

    @Value("${weather.api.latitude:}")
    private String latitudeConfig;

    @Value("${weather.api.longitude:}")
    private String longitudeConfig;

    private double latitude;
    private double longitude;
    private boolean configurationChecked;

    /** Which hourly params to request (default: cloudcover). */
    @Value("${weather.api.hourly-params:cloudcover}")
    private String hourlyParams;

    /** Percentage threshold defining "sunny enough". */
    @Value("${weather.sunny-threshold:40}")
    private int sunnyThresholdPct;

    /** Max hours to look ahead (guard in case wait window is very long). */
    @Value("${weather.lookahead.hours:12}")
    private int lookaheadHours;

    @Autowired
    private RestTemplate restTemplate;

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @PostConstruct
    void validateConfiguration() {
        ensureConfiguration();
    }

    public boolean isEnabled() {
        ensureConfiguration();
        return enabled;
    }

    private synchronized void ensureConfiguration() {
        if (configurationChecked) return;
        configurationChecked = true;
        if (!enabled) return;
        try {
            if (latitudeConfig == null || latitudeConfig.isBlank()
                    || longitudeConfig == null || longitudeConfig.isBlank()) {
                throw new IllegalArgumentException("coordinates are missing");
            }
            double configuredLatitude = Double.parseDouble(latitudeConfig.trim());
            double configuredLongitude = Double.parseDouble(longitudeConfig.trim());
            if (!Double.isFinite(configuredLatitude) || configuredLatitude < -90 || configuredLatitude > 90
                    || !Double.isFinite(configuredLongitude) || configuredLongitude < -180
                    || configuredLongitude > 180) {
                throw new IllegalArgumentException("coordinates are invalid");
            }
            latitude = configuredLatitude;
            longitude = configuredLongitude;
        } catch (IllegalArgumentException ex) {
            enabled = false;
            LogFilter.logWarn(getClass(),
                    "Weather forecast disabled: set valid weather.api.latitude and weather.api.longitude.");
        }
    }

    private record Hour(int cover, double radiation) {}
    private NavigableMap<Long, Hour> forecast = new TreeMap<>();
    private long fetchedAt;
    private long lastAttempt;
    private static final long HOUR = 3_600_000L;

    // Publish only complete snapshots. Keep the previous forecast through short outages.
    private synchronized NavigableMap<Long, Hour> snapshot(long now) {
        if (!isEnabled()) return new TreeMap<>();
        if (now - fetchedAt >= HOUR && now - lastAttempt >= 300_000L) {
            lastAttempt = now;
            try {
                String params = hourlyParams.replace("cloudcover", "cloud_cover");
                var variables = new java.util.LinkedHashSet<>(java.util.Arrays.asList(params.split(",")));
                variables.add("cloud_cover");
                variables.add("shortwave_radiation");
                URI uri = URI.create(baseUrl + "?latitude=" + latitude + "&longitude=" + longitude
                        + "&hourly=" + URLEncoder.encode(String.join(",", variables), StandardCharsets.UTF_8)
                        + "&timeformat=unixtime&timezone=GMT");
                JsonNode hourly = mapper.readTree(restTemplate.getForObject(uri, String.class)).path("hourly");
                var times = hourly.path("time");
                var cover = hourly.path("cloud_cover");
                var radiation = hourly.path("shortwave_radiation");
                var fresh = new TreeMap<Long, Hour>();
                if (!times.isArray() || !cover.isArray() || times.size() != cover.size())
                    throw new IllegalArgumentException("Incomplete hourly forecast");
                for (int i = 0; i < times.size(); i++) {
                    if (!times.get(i).isIntegralNumber() || !cover.get(i).isNumber()) continue;
                    int pct = cover.get(i).asInt();
                    if (pct < 0 || pct > 100) continue;
                    var rad = radiation.path(i);
                    fresh.put(times.get(i).asLong() * 1000, new Hour(pct, rad.isNumber() ? rad.asDouble() : -1));
                }
                long floor = Math.floorDiv(now, HOUR) * HOUR;
                for (long t = floor; t <= floor + Math.max(1, lookaheadHours) * HOUR; t += HOUR)
                    if (!fresh.containsKey(t)) throw new IllegalArgumentException("Forecast horizon has gaps");
                forecast = fresh;
                fetchedAt = now;
                LogFilter.logInfo(getClass(), "Open-Meteo hourly forecast refreshed.");
            } catch (Exception ex) {
                LogFilter.logWarn(getClass(), "Weather refresh failed; last successful fetch (epoch ms): {}. {}",
                        fetchedAt, ex.getMessage());
            }
        }
        return now - fetchedAt <= 3 * HOUR ? forecast : new TreeMap<>();
    }

    public Optional<Long> findSunnySlot(long now, long latest) {
        long end = Math.min(latest, now + lookaheadHours * HOUR);
        if (end <= now) return Optional.empty();
        var hours = snapshot(now);
        return hours.subMap(now, false, end, true).entrySet().stream()
                .filter(e -> !ChargingUtils.isNight(e.getKey()) && e.getValue().cover <= sunnyThresholdPct)
                .filter(e -> {
                    // Radiation timestamp is the END of the averaging interval.
                    Hour next = hours.get(e.getKey() + HOUR);
                    return next != null && next.radiation > 0;
                })
                .map(java.util.Map.Entry::getKey).findFirst();
    }

    /** Shadow-only grid target. Reserve must cover every prefix before the forecast PV arrives. */
    public Optional<Integer> previewGridTarget(long now, int seasonal, int reserve, double capacityWh,
                                                double pvKwp, double householdW) {
        if (pvKwp <= 0 || capacityWh <= 0 || householdW <= 0 || reserve >= seasonal) return Optional.empty();
        var hours = snapshot(now);
        long start = now;
        double balance = 0, deficit = 0;
        for (int i = 0; i < lookaheadHours; i++) {
            long end = (Math.floorDiv(start, HOUR) + 1) * HOUR;
            Hour h = hours.get(end);
            if (h == null || h.radiation < 0) return Optional.empty();
            balance += (pvKwp * h.radiation * 0.75 - householdW) * (end - start) / HOUR;
            deficit = Math.max(deficit, -balance);
            start = end;
        }
        int headroom = (int) Math.floor(100 * Math.max(0, balance) / capacityWh);
        int beforeSun = reserve + (int) Math.ceil(100 * deficit / capacityWh);
        return Optional.of(Math.min(seasonal, Math.max(beforeSun, seasonal - headroom)));
    }
}
