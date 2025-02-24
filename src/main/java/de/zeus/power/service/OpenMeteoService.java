package de.zeus.power.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.zeus.power.config.LogFilter;
import de.zeus.power.util.ChargingUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Optional;

@Service
public class OpenMeteoService {

    @Value("${weather.api.base-url}")
    private String baseUrl;

    @Value("${weather.api.latitude}")
    private double latitude;

    @Value("${weather.api.longitude}")
    private double longitude;

    @Value("${weather.api.hourly-params}")
    private String hourlyParams;

    @Value("${weather.api.enabled}")
    private boolean isWeatherApiEnabled;

    @Value("${night.start}")
    private int nightStartHour;

    @Value("${night.end}")
    private int nightEndHour;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OpenMeteoService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches the cloud cover data for the current hour.
     * Ignores weather data if it is nighttime (based on configured hours) or if the API is disabled.
     *
     * @return Optional<Double> representing the cloud cover percentage (0 to 100).
     */
    public Optional<Double> getCurrentCloudCover() {
        if (!isWeatherApiEnabled) {
            LogFilter.logInfo(OpenMeteoService.class, "Weather API is disabled. Skipping cloud cover check.");
            return Optional.empty();
        }

        if (ChargingUtils.isNight(System.currentTimeMillis())) {
            LogFilter.logInfo(OpenMeteoService.class, "Ignoring weather data as it is nighttime ({}:00 to {}:00).", nightStartHour, nightEndHour);
            return Optional.empty();
        }

        String url = buildWeatherApiUrl();
        LogFilter.logDebug(OpenMeteoService.class, "Fetching cloud cover from URL: {}", url);

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                LogFilter.logWarn(OpenMeteoService.class, "Weather API call failed with status: {}", response.getStatusCode());
                return Optional.empty();
            }

            JsonNode hourlyNode = parseCloudCoverNode(response.getBody());
            if (hourlyNode != null && hourlyNode.isArray() && !hourlyNode.isEmpty()) {
                double cloudCover = hourlyNode.get(0).asDouble();
                LogFilter.logInfo(OpenMeteoService.class, "Fetched cloud cover data: {}%", cloudCover);
                return Optional.of(cloudCover);
            } else {
                LogFilter.logWarn(OpenMeteoService.class, "No valid cloud cover data in API response.");
                return Optional.empty();
            }
        } catch (Exception e) {
            LogFilter.logError(OpenMeteoService.class, "Failed to fetch weather data: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String buildWeatherApiUrl() {
        return UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("latitude", latitude)
                .queryParam("longitude", longitude)
                .queryParam("hourly", hourlyParams)
                .toUriString();
    }

    private JsonNode parseCloudCoverNode(String responseBody) throws Exception {
        JsonNode rootNode = objectMapper.readTree(responseBody);
        return rootNode.path("hourly").path("cloudcover");
    }
}