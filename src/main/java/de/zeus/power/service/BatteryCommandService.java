package de.zeus.power.service;

import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.BatteryStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;

/**
 * Copyright 2024 Guido Zeuner - https://tiny-tool.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@Service
public class BatteryCommandService {

    // Logger instance for logging events and errors in this service
    private static final Logger LOG = LoggerFactory.getLogger(BatteryCommandService.class);

    // Constants for API endpoint paths
    private static final String CONFIGURATIONS_PATH = "/configurations";
    private static final String STATUS_PATH = "/status";
    private static final String SETPOINT_PATH = "/setpoint";
    private static final String CHARGE_PATH = "/charge/";
    private static final String DISCHARGE_PATH = "/discharge/";

    // Constant for the authentication header key
    private static final String AUTH_HEADER = "Auth-Token";

    // Constant for the content type used in configuration requests
    private static final MediaType FORM_URLENCODED = MediaType.APPLICATION_FORM_URLENCODED;

    // Authentication token for the battery API, injected from configuration
    @Value("${battery.authToken}")
    private String authToken;

    // Base URL for the battery API, injected from configuration
    @Value("${battery.url}")
    private String url;

    // Flag indicating if the battery configuration is incomplete
    private boolean batteryNotConfigured;

    // REST client for making HTTP requests to the battery API
    private RestTemplate restTemplate;

    /**
     * Initializes the service after construction.
     * Checks if the configuration (authToken and url) is valid and sets up the RestTemplate.
     */
    @PostConstruct
    public void init() {
        batteryNotConfigured = authToken.isEmpty() || url.isEmpty();
        if (batteryNotConfigured) {
            LOG.warn("Battery configuration is incomplete. authToken or url is missing.");
        } else {
            restTemplate = new RestTemplateBuilder()
                    .defaultHeader(AUTH_HEADER, authToken)
                    .build();
        }
    }

    /**
     * Checks if the battery is not configured properly.
     *
     * @return True if the battery configuration is incomplete, false otherwise.
     */
    public boolean isBatteryNotConfigured() {
        return batteryNotConfigured;
    }

    /**
     * Retrieves the current battery status via a GET request to the API.
     *
     * @return An ApiResponse containing the BatteryStatusResponse or an error message.
     */
    public ApiResponse<BatteryStatusResponse> getStatus() {
        try {
            ResponseEntity<BatteryStatusResponse> response = restTemplate.getForEntity(buildUrl(STATUS_PATH), BatteryStatusResponse.class);
            BatteryStatusResponse data = response.getBody();
            return new ApiResponse<>(
                    data != null,
                    response.getStatusCode(),
                    data != null ? "Battery status retrieved successfully" : "No Status available",
                    data
            );
        } catch (Exception e) {
            LOG.error("Error occurred while retrieving battery status", e);
            return errorResponse("Internal Server Error");
        }
    }

    /**
     * Sets the charge point of the battery in watts via a POST request.
     *
     * @param watt The charge point value in watts to set.
     * @return An ApiResponse indicating success or failure of the operation.
     */
    public ApiResponse<?> setChargePoint(int watt) {
        String endpoint = buildUrl(SETPOINT_PATH + CHARGE_PATH + watt);
        return executePostRequest(endpoint, "set charge point", HttpStatus.CREATED);
    }

    /**
     * Sets the discharge point of the battery in watts via a POST request.
     *
     * @param watt The discharge point value in watts to set.
     * @return An ApiResponse indicating success or failure of the operation.
     */
    public ApiResponse<?> setDischargePoint(int watt) {
        String endpoint = buildUrl(SETPOINT_PATH + DISCHARGE_PATH + watt);
        return executePostRequest(endpoint, "set discharge point", HttpStatus.CREATED);
    }

    /**
     * Sets a configuration key-value pair for the battery via a PUT request.
     *
     * @param key The configuration key to set.
     * @param value The configuration value to set.
     * @return An ApiResponse indicating success or failure of the operation.
     */
    public ApiResponse<?> setConfiguration(String key, String value) {
        String endpoint = buildUrl(CONFIGURATIONS_PATH);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(FORM_URLENCODED);

        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add(key, value);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.PUT, request, String.class);
            LOG.info("Request to set configuration sent. URL: {}, Key: {}, Value: {}", endpoint, key, value);
            LOG.debug("Response status: {}, Body: {}", response.getStatusCode(), response.getBody());

            boolean success = response.getStatusCode() == HttpStatus.OK;
            String message = getConfigurationMessage(response.getStatusCode(), key, value);
            return new ApiResponse<>(success, response.getStatusCode(), message, response.getBody());
        } catch (Exception e) {
            LOG.error("Error occurred while sending request to set configuration", e);
            return errorResponse("Internal Server Error", e.getMessage());
        }
    }

    // Helper Methods

    /**
     * Builds the full URL for an API request by appending the path to the base URL.
     *
     * @param path The endpoint path to append.
     * @return The complete URL as a string.
     */
    private String buildUrl(String path) {
        return url + path;
    }

    /**
     * Creates an error response for BatteryStatusResponse type requests.
     *
     * @param message The error message to include.
     * @return An ApiResponse with failure status and the specified message.
     */
    private ApiResponse<BatteryStatusResponse> errorResponse(String message) {
        return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, message, null);
    }

    /**
     * Creates an error response with additional error details for String type requests.
     *
     * @param message The error message to include.
     * @param errorDetail Additional details about the error (e.g., exception message).
     * @return An ApiResponse with failure status, message, and details.
     */
    private ApiResponse<String> errorResponse(String message, String errorDetail) {
        return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, message, errorDetail);
    }

    /**
     * Executes a POST request to the specified endpoint and handles the response.
     *
     * @param endpoint The API endpoint to send the request to.
     * @param action The action description for logging (e.g., "set charge point").
     * @param successStatus The expected HTTP status for a successful response.
     * @return An ApiResponse indicating the result of the POST request.
     */
    private ApiResponse<String> executePostRequest(String endpoint, String action, HttpStatus successStatus) {
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(endpoint, null, String.class);
            LOG.info("Request to {} sent. URL: {}", action, endpoint);
            LOG.debug("Response status: {}, Body: {}", response.getStatusCode(), response.getBody());

            boolean success = response.getStatusCode() == successStatus;
            String message = getStatusMessage(response.getStatusCode(), action);
            return new ApiResponse<>(success, response.getStatusCode(), message, response.getBody());
        } catch (Exception e) {
            LOG.error("Error occurred while sending request to {}", action, e);
            return errorResponse("Internal Server Error", e.getMessage());
        }
    }

    /**
     * Generates a status message based on the HTTP response status and action.
     *
     * @param status The HTTP status code from the response.
     * @param action The action description (e.g., "set charge point").
     * @return A string message describing the outcome.
     */
    private String getStatusMessage(HttpStatus status, String action) {
        return switch (status) {
            case CREATED -> "Successfully " + action;
            case UNAUTHORIZED -> "Unauthorized";
            case FORBIDDEN -> "Forbidden - VPP has priority";
            default -> "Unknown error";
        };
    }

    /**
     * Generates a configuration message based on the HTTP response status, key, and value.
     *
     * @param status The HTTP status code from the response.
     * @param key The configuration key set.
     * @param value The configuration value set.
     * @return A string message describing the outcome of the configuration update.
     */
    private String getConfigurationMessage(HttpStatus status, String key, String value) {
        return switch (status) {
            case OK -> "Configuration updated: " + key + " = " + value;
            case UNAUTHORIZED -> "Unauthorized";
            case FORBIDDEN -> "Forbidden";
            default -> "Unknown error";
        };
    }
}