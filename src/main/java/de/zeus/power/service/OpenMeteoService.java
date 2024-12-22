package de.zeus.power.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.time.LocalTime;
import java.util.Optional;

@Service
public class OpenMeteoService {

    private static final Logger logger = LoggerFactory.getLogger(OpenMeteoService.class);

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
     * Ignores weather data if it is nighttime (based on configured hours).
     *
     * @return Optional<Double> representing the cloud cover percentage (0 to 100).
     */
    public Optional<Double> getCurrentCloudCover() {
        if (!isWeatherApiEnabled) {
            logger.info("Weather API is disabled. Skipping cloud cover check.");
            return Optional.empty();
        }

        LocalTime now = LocalTime.now();
        if (isNightTime(now)) {
            logger.info("Ignoring weather data as it is nighttime ({}:00 to {}:00).", nightStartHour, nightEndHour);
            return Optional.empty();
        }

        // Generate URL manually
        String url = baseUrl + "?latitude=" + latitude + "&longitude=" + longitude + "&hourly=" + hourlyParams;
        logger.debug("Constructed Weather API URL: {}", url);

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            logger.debug("Weather API response: {}", response.getBody());

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                JsonNode hourlyNode = rootNode.path("hourly").path("cloudcover");
                if (hourlyNode.isArray() && hourlyNode.size() > 0) {
                    double cloudCover = hourlyNode.get(0).asDouble();
                    logger.info("Fetched cloud cover data: {}%", cloudCover);
                    return Optional.of(cloudCover);
                } else {
                    logger.warn("No cloud cover data available in the API response.");
                }
            } else {
                logger.warn("Weather API call failed with status: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Error occurred while fetching weather data: ", e);
        }

        return Optional.empty();
    }


    private boolean isNightTime(LocalTime now) {
        LocalTime nightStart = LocalTime.of(nightStartHour, 0);
        LocalTime nightEnd = LocalTime.of(nightEndHour, 0);

        if (nightStart.isBefore(nightEnd)) {
            return now.isAfter(nightStart) && now.isBefore(nightEnd);
        } else {
            return now.isAfter(nightStart) || now.isBefore(nightEnd);
        }
    }

}
