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
 * Copyright 2024 Guido Zeuner
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * © 2024 - Guido Zeuner - https://tiny-tool.de
 *
 * ChargeControl - Release V 1.1
 *
 * Author: Guido Zeuner
 */


@Service
public class BatteryCommandService {

    private static final Logger logger = LoggerFactory.getLogger(BatteryCommandService.class);
    public static final String CONFIGURATIONS = "/configurations";
    public static final String STATUS = "/status";
    public static final String SETPOINT = "/setpoint";
    public static final String CHARGE = "/charge/";
    public static final String DISCHARGE = "/discharge/";

    @Value("${battery.authToken}")
    private String authToken;

    @Value("${battery.url}")
    private String url;

    private boolean batteryNotConfigured;

    private RestTemplate restTemplate;

    /**
     * Initializes the RestTemplate with the default authentication header.
     */
    @PostConstruct
    public void init() {
        if (authToken.isEmpty() || url.isEmpty()) {
            batteryNotConfigured = true;
            logger.warn("Battery configuration is incomplete. authToken or url is missing.");
        } else {
            batteryNotConfigured = false;
            restTemplate = new RestTemplateBuilder()
                    .defaultHeader("Auth-Token", authToken)
                    .build();
        }
    }

    public boolean isBatteryNotConfigured() {
        return batteryNotConfigured;
    }

    /**
     * Retrieves the battery status.
     *
     * @return ApiResponse containing the battery status or an error message.
     */
    public ApiResponse<BatteryStatusResponse> getStatus() {
        try {
            ResponseEntity<BatteryStatusResponse> response = restTemplate.getForEntity(url + STATUS, BatteryStatusResponse.class);
            BatteryStatusResponse batteryStatusResponse = response.getBody();
            String message = batteryStatusResponse != null ? "Battery status retrieved successfully" : "No Status available";

            return new ApiResponse<>(
                    batteryStatusResponse != null,
                    response.getStatusCode(),
                    message,
                    batteryStatusResponse);
        } catch (Exception e) {
            logger.error("Error occurred while retrieving battery status", e);
            return new ApiResponse<>(
                    false,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Internal Server Error",
                    null);
        }
    }

    /**
     * Sets the charge point of the battery.
     *
     * @param watt the charge point in watts.
     * @return ApiResponse containing the result of the operation.
     */
    public ApiResponse<?> setChargePoint(int watt) {
        String url = this.url + SETPOINT + CHARGE + watt;
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);
            logger.info("Request to set charge point sent. URL: " + url);
            logger.debug("Response status code: " + response.getStatusCode());
            logger.debug("Response body: " + response.getBody());

            boolean success = response.getStatusCode() == HttpStatus.CREATED;
            String message = switch (response.getStatusCode()) {
                case CREATED -> "Successfully set charge point";
                case UNAUTHORIZED -> "Unauthorized";
                case FORBIDDEN -> "Forbidden - VPP has priority";
                default -> "Unknown error";
            };
            return new ApiResponse<>(success, response.getStatusCode(),
                    message, response.getBody());
        } catch (Exception e) {
            logger.error("Error occurred while sending request to set charge point", e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Internal Server Error", e.getMessage());
        }
    }

    /**
     * Sets the discharge point of the battery.
     *
     * @param watt the discharge point in watts.
     * @return ApiResponse containing the result of the operation.
     */
    public ApiResponse<?> setDischargePoint(int watt) {
        String url = this.url + SETPOINT + DISCHARGE + watt;
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);
            logger.info("Request to set discharge point sent. URL: " + url);
            logger.debug("Response status code: " + response.getStatusCode());
            logger.debug("Response body: " + response.getBody());

            boolean success = response.getStatusCode() == HttpStatus.CREATED;
            String message = switch (response.getStatusCode()) {
                case CREATED -> "Successfully set discharge point";
                case UNAUTHORIZED -> "Unauthorized";
                case FORBIDDEN -> "Forbidden - VPP has priority";
                default -> "Unknown error";
            };
            return new ApiResponse<>(success, response.getStatusCode(), message, response.getBody());
        } catch (Exception e) {
            logger.error("Error occurred while sending request to set discharge point", e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Internal Server Error", e.getMessage());
        }
    }

    /**
     * Sets a configuration key-value pair for the battery.
     *
     * @param key the configuration key.
     * @param value the configuration value.
     * @return ApiResponse containing the result of the operation.
     */
    public ApiResponse<?> setConfiguration(String key, String value) {
        String url = this.url+ CONFIGURATIONS;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add(key, value);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, request, String.class);
            logger.info("Request to set configuration sent. URL: " + url + ", Key: " + key + ", Value: " + value);
            logger.debug("Response status code: " + response.getStatusCode());
            logger.debug("Response body: " + response.getBody());

            boolean success = response.getStatusCode() == HttpStatus.OK;
            String message = switch (response.getStatusCode()) {
                case OK -> "Configuration updated: " + key + " = " + value;
                case UNAUTHORIZED -> "Unauthorized";
                case FORBIDDEN -> "Forbidden";
                default -> "Unknown error";
            };
            return new ApiResponse<>(success, response.getStatusCode(), message, response.getBody());
        } catch (Exception e) {
            logger.error("Error occurred while sending request to set configuration", e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Internal Server Error", e.getMessage());
        }
    }
}

