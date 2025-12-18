package de.zeus.power.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.zeus.power.config.LogFilter;
import de.zeus.power.util.ChargingUtils;
import de.zeus.power.util.NightConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Simple Open-Meteo client to fetch hourly cloud cover and decide whether
 * it's worth waiting for sunshine (PV) instead of grid-charging.
 */
@Service
public class WeatherForecastService {

    @Value("${weather.api.enabled:true}")
    private boolean enabled;

    @Value("${weather.api.base-url:https://api.open-meteo.com/v1/forecast}")
    private String baseUrl;

    @Value("${weather.api.latitude:52.52}")
    private double latitude;

    @Value("${weather.api.longitude:13.405}")
    private double longitude;

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

    private final ObjectMapper mapper = new ObjectMapper();

    public boolean isEnabled() { return enabled; }

    /**
     * Returns the first epoch millis of an hourly slot with cloud cover
     * <= threshold between now and latestStartMillis. Only returns daytime
     * (outside NightConfig hours).
     */
    public Optional<Long> findSunnySlot(long nowMillis, long latestStartMillis) {
        if (!enabled) return Optional.empty();
        try {
            long horizonMillis = Math.min(latestStartMillis, nowMillis + lookaheadHours * 3600_000L);
            // Build Open-Meteo URL. We request hourly=cloudcover and timezone=auto
            String url = String.format(
                    "%s?latitude=%s&longitude=%s&hourly=%s&timezone=auto",
                    baseUrl,
                    URLEncoder.encode(String.valueOf(latitude), StandardCharsets.UTF_8),
                    URLEncoder.encode(String.valueOf(longitude), StandardCharsets.UTF_8),
                    URLEncoder.encode(hourlyParams, StandardCharsets.UTF_8)
            );
            ResponseEntity<String> resp = restTemplate.getForEntity(URI.create(url), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                LogFilter.logWarn(WeatherForecastService.class, "Open-Meteo request failed: {}", resp.getStatusCode());
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(resp.getBody());
            JsonNode hourly = root.path("hourly");
            if (hourly.isMissingNode()) return Optional.empty();

            JsonNode times = hourly.path("time");
            JsonNode covers = hourly.path("cloudcover");
            if (!times.isArray() || !covers.isArray()) return Optional.empty();

            int n = Math.min(times.size(), covers.size());
            for (int i = 0; i < n; i++) {
                String ts = times.get(i).asText();          // ISO8601 local time (timezone=auto)
                int cover = covers.get(i).asInt(100);
                // Parse to epoch millis
                long t = Instant.parse(ZonedDateTime.parse(ts + "Z").toInstant().toString()).toEpochMilli();
                if (t < nowMillis || t > horizonMillis) continue;
                if (ChargingUtils.isNight(t)) continue;      // only consider daytime slots
                if (cover <= sunnyThresholdPct) {
                    return Optional.of(t);
                }
            }
        } catch (Exception ex) {
            LogFilter.logWarn(WeatherForecastService.class, "Weather parsing failed: {}", ex.getMessage());
        }
        return Optional.empty();
    }
}
