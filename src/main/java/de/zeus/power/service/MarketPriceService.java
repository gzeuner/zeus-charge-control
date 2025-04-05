package de.zeus.power.service;

import de.zeus.power.config.LogFilter;
import de.zeus.power.entity.MarketPrice;
import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.MarketPriceResponse;
import de.zeus.power.model.TibberResponse;
import de.zeus.power.repository.MarketPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service class for retrieving and managing market prices from external APIs (Tibber and Awattar).
 *
 * Copyright 2024 Guido Zeuner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
 */
@Service
public class MarketPriceService {

    private static final Logger logger = LoggerFactory.getLogger(MarketPriceService.class);
    private static final String AWATTAR = "awattar";
    private static final String TIBBER = "tibber";
    private static final String CENT_KWH = "cent/kWh";
    private static final double CONVERSION_FACTOR = 100.0;

    @Autowired
    private MarketPriceRepository marketPriceRepository;

    @Value("${marketdata.source}")
    private String marketDataSource;

    @Value("${awattar.marketdata.url}")
    private String awattarUrl;

    @Value("${awattar.authToken}")
    private String awattarAuthToken;

    @Value("${tibber.marketdata.url}")
    private String tibberUrl;

    @Value("${tibber.authToken}")
    private String tibberAuthToken;

    @Value("${tibber.query.today}")
    private String tibberQueryToday;

    @Value("${tibber.query.tomorrow}")
    private String tibberQueryTomorrow;

    @Value("${battery.accepted.delay}")
    private int acceptedDelayInMinutes;

    private final RestTemplate restTemplate;

    public MarketPriceService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }

    /**
     * Retrieves market prices from the configured data source with a fallback mechanism.
     * Attempts fetching from the primary source (Tibber or Awattar) and falls back to the other if necessary.
     *
     * @return ApiResponse containing the market price data or an error message.
     */
    public ApiResponse<MarketPriceResponse> getMarketPrices() {
        ApiResponse<MarketPriceResponse> response = null;

        if (TIBBER.equalsIgnoreCase(marketDataSource)) {
            logger.info("Fetching market prices from Tibber...");
            response = getTibberPrices();
            if (response.success() && response.data() != null && !response.data().getData().isEmpty()) {
                logger.info("Successfully retrieved market prices from Tibber.");
                return response;
            } else {
                logger.warn("Failed to retrieve valid data from Tibber (Status: {}). Falling back to Awattar...", response.statusCode());
            }
        }

        if (response == null || !response.success()) {
            logger.info("Fetching market prices from Awattar...");
            response = getAwattarPrices();
            if (response.success() && response.data() != null && !response.data().getData().isEmpty()) {
                logger.info("Successfully retrieved market prices from Awattar.");
                return response;
            } else {
                logger.error("Failed to retrieve valid data from Awattar (Status: {}). No valid prices available.", response.statusCode());
            }
        }

        return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to retrieve market prices from all sources", null);
    }

    /**
     * Retrieves market prices from Awattar with retry logic for transient failures.
     *
     * @return ApiResponse containing the combined market price data or an error message.
     */
    @Retryable(value = { RestClientException.class }, maxAttempts = 3, backoff = @Backoff(delay = 2000))
    private ApiResponse<MarketPriceResponse> getAwattarPrices() {
        try {
            long todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000;
            long tomorrowStart = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000;

            String urlToday = awattarUrl + "?start=" + todayStart;
            String urlTomorrow = awattarUrl + "?start=" + tomorrowStart;

            ResponseEntity<MarketPriceResponse> responseToday = fetchAwattarPrices(urlToday);
            ResponseEntity<MarketPriceResponse> responseTomorrow = fetchAwattarPrices(urlTomorrow);

            if (responseToday.getStatusCode().is2xxSuccessful() && responseTomorrow.getStatusCode().is2xxSuccessful() &&
                    responseToday.getBody() != null && responseTomorrow.getBody() != null) {
                MarketPriceResponse combinedResponse = combineAwattarResponses(responseToday.getBody(), responseTomorrow.getBody());
                return new ApiResponse<>(true, HttpStatus.OK, "Market prices retrieved successfully from Awattar", combinedResponse);
            } else {
                return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to retrieve complete market prices from Awattar", null);
            }
        } catch (RestClientException e) {
            logger.warn("Transient error fetching Awattar prices, retrying... Error: {}", e.getMessage());
            throw e; // Trigger retry
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Fetches market prices from Awattar using the provided URL.
     *
     * @param url The URL to fetch market prices from.
     * @return ResponseEntity containing the market price data.
     */
    private ResponseEntity<MarketPriceResponse> fetchAwattarPrices(String url) {
        HttpHeaders headers = createHeaders(awattarAuthToken);
        HttpEntity<String> request = new HttpEntity<>(headers);
        return restTemplate.exchange(url, HttpMethod.GET, request, MarketPriceResponse.class);
    }

    /**
     * Combines today's and tomorrow's market price data from Awattar into a single response.
     *
     * @param today Today's market price data.
     * @param tomorrow Tomorrow's market price data.
     * @return Combined MarketPriceResponse with data from today and tomorrow.
     */
    private MarketPriceResponse combineAwattarResponses(MarketPriceResponse today, MarketPriceResponse tomorrow) {
        today.getData().addAll(tomorrow.getData());
        return convertAwattarToCentPerKWh(today);
    }

    /**
     * Retrieves market prices from Tibber with retry logic for transient failures.
     *
     * @return ApiResponse containing the combined market price data or an error message.
     */
    @Retryable(value = { RestClientException.class }, maxAttempts = 3, backoff = @Backoff(delay = 2000))
    private ApiResponse<MarketPriceResponse> getTibberPrices() {
        try {
            ResponseEntity<TibberResponse> responseToday = fetchTibberPrices(tibberQueryToday);
            ResponseEntity<TibberResponse> responseTomorrow = fetchTibberPrices(tibberQueryTomorrow);

            boolean hasTomorrowData = responseTomorrow.getBody() != null &&
                    responseTomorrow.getBody().getData() != null &&
                    !responseTomorrow.getBody().getData().getViewer().getHomes().get(0).getCurrentSubscription().getPriceInfo().getTomorrow().isEmpty();

            if (responseToday.getStatusCode().is2xxSuccessful() && responseToday.getBody() != null) {
                MarketPriceResponse marketPriceResponse = mapTibberToMarketPriceResponse(responseToday.getBody(), hasTomorrowData ? responseTomorrow.getBody() : null);

                if (!hasTomorrowData) {
                    logger.warn("Tibber did not provide prices for tomorrow. Attempting to fetch prices from Awattar...");
                    ApiResponse<MarketPriceResponse> awattarResponse = getAwattarPrices();
                    if (awattarResponse.success() && awattarResponse.data() != null) {
                        logger.info("Successfully fetched missing prices for tomorrow from Awattar.");
                        marketPriceResponse.getData().addAll(awattarResponse.data().getData().stream()
                                .filter(data -> Instant.ofEpochMilli(data.getStartTimestamp())
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                        .isEqual(LocalDate.now().plusDays(1)))
                                .collect(Collectors.toList()));
                    } else {
                        logger.error("Failed to retrieve fallback prices from Awattar: {}", awattarResponse.message());
                    }
                }

                return new ApiResponse<>(true, HttpStatus.OK, "Market prices retrieved successfully from Tibber", marketPriceResponse);
            } else {
                return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to retrieve market prices from Tibber", null);
            }
        } catch (RestClientException e) {
            logger.warn("Transient error fetching Tibber prices, retrying... Error: {}", e.getMessage());
            throw e; // Trigger retry
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Fetches market prices from Tibber using the provided query.
     *
     * @param query The GraphQL query to fetch market prices.
     * @return ResponseEntity containing the market price data.
     */
    private ResponseEntity<TibberResponse> fetchTibberPrices(String query) {
        HttpHeaders headers = createHeaders(tibberAuthToken);
        HttpEntity<String> request = new HttpEntity<>(query, headers);
        return restTemplate.postForEntity(tibberUrl, request, TibberResponse.class);
    }

    /**
     * Creates HTTP headers for API requests, including the authorization token if provided.
     *
     * @param token The authorization token.
     * @return HttpHeaders with the necessary headers for the request.
     */
    private HttpHeaders createHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null && !token.isEmpty()) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    /**
     * Handles exceptions that occur during the retrieval of market prices, supporting all RestClientException subclasses.
     *
     * @param e The exception that occurred.
     * @return ApiResponse with an error message and status.
     */
    private ApiResponse<MarketPriceResponse> handleException(Exception e) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = "Internal server error";

        if (e instanceof RestClientException) {
            if (e instanceof org.springframework.web.client.HttpClientErrorException) {
                status = ((org.springframework.web.client.HttpClientErrorException) e).getStatusCode();
                message = "Client error: " + ((org.springframework.web.client.HttpClientErrorException) e).getStatusText();
            } else if (e instanceof org.springframework.web.client.HttpServerErrorException) {
                status = ((org.springframework.web.client.HttpServerErrorException) e).getStatusCode();
                message = "Server error: " + ((org.springframework.web.client.HttpServerErrorException) e).getStatusText();
            }
            logger.error("HTTP error occurred while retrieving market prices: {} (Status: {})", message, status, e);
        } else {
            logger.error("Unexpected error occurred while retrieving market prices", e);
        }
        return new ApiResponse<>(false, status, message, null);
    }

    /**
     * Converts the market price data from Awattar to cent/kWh and rounds to 2 decimal places.
     *
     * @param response The MarketPriceResponse containing Awattar market price data.
     * @return MarketPriceResponse with converted market price data.
     */
    private MarketPriceResponse convertAwattarToCentPerKWh(MarketPriceResponse response) {
        response.getData().forEach(data -> {
            data.setMarketprice(Math.round((data.getMarketprice() / 10) * CONVERSION_FACTOR) / CONVERSION_FACTOR); // Convert Eur/MWh to cent/kWh
            data.setUnit(CENT_KWH);
        });
        return response;
    }

    /**
     * Maps the market price data from Tibber to a MarketPriceResponse.
     *
     * @param today Today's market price data from Tibber.
     * @param tomorrow Tomorrow's market price data from Tibber.
     * @return MarketPriceResponse with combined market price data from today and tomorrow.
     */
    private MarketPriceResponse mapTibberToMarketPriceResponse(TibberResponse today, TibberResponse tomorrow) {
        List<MarketPriceResponse.MarketData> marketDataList = mapTibberPriceData(today.getData().getViewer().getHomes().get(0).getCurrentSubscription().getPriceInfo().getToday());

        if (tomorrow != null && tomorrow.getData() != null) {
            marketDataList.addAll(mapTibberPriceData(tomorrow.getData().getViewer().getHomes().get(0).getCurrentSubscription().getPriceInfo().getTomorrow()));
        } else {
            LogFilter.logWarn(MarketPriceService.class, "No data available for tomorrow in Tibber response.");
        }

        MarketPriceResponse marketPriceResponse = new MarketPriceResponse();
        marketPriceResponse.setObject("list");
        marketPriceResponse.setData(marketDataList);
        return marketPriceResponse;
    }

    /**
     * Maps Tibber price data to a list of MarketPriceResponse.MarketData objects.
     *
     * @param priceDataList List of Tibber price data.
     * @return List of MarketPriceResponse.MarketData objects with converted price data.
     */
    private List<MarketPriceResponse.MarketData> mapTibberPriceData(List<TibberResponse.PriceData> priceDataList) {
        return priceDataList.stream().map(data -> {
            MarketPriceResponse.MarketData marketData = new MarketPriceResponse.MarketData();
            marketData.setStartTimestamp(Instant.parse(data.getStartsAt()).toEpochMilli());
            marketData.setEndTimestamp(Instant.parse(data.getStartsAt()).plus(1, ChronoUnit.HOURS).toEpochMilli());
            marketData.setMarketprice(Math.round((data.getEnergy() * CONVERSION_FACTOR) * CONVERSION_FACTOR) / CONVERSION_FACTOR); // Convert Eur/kWh to cent/kWh
            marketData.setUnit(CENT_KWH);
            return marketData;
        }).collect(Collectors.toList());
    }

    /**
     * Retrieves all valid market prices from the repository based on the current time and accepted delay.
     *
     * @return List of valid MarketPrice entities.
     */
    public List<MarketPrice> getAllMarketPrices() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        long currentTime = now.atZone(ZoneId.systemDefault()).toEpochSecond() * 1000;
        long pastTime = now.minusMinutes(acceptedDelayInMinutes).atZone(ZoneId.systemDefault()).toEpochSecond() * 1000;

        return marketPriceRepository.findValidMarketPrices(currentTime, pastTime);
    }

    /**
     * Retrieves the current market price based on the current time from the database.
     *
     * @return The current price in cent/kWh or null if no matching period exists.
     */
    public Double getCurrentPriceForTimeRange() {
        long currentTime = Instant.now().toEpochMilli();
        List<MarketPrice> prices = marketPriceRepository.findValidMarketPrices(currentTime, Instant.now().minus(acceptedDelayInMinutes, ChronoUnit.MINUTES).toEpochMilli());
        return prices.stream()
                .filter(p -> p.getStartTimestamp() <= currentTime && p.getEndTimestamp() > currentTime)
                .map(MarketPrice::getMarketPrice)
                .findFirst()
                .orElse(null);
    }

    /**
     * Retrieves the currently valid market price based on the current time.
     *
     * @return The current price in cent/kWh or null if no valid price exists for the current time.
     */
    public Double getCurrentlyValidPrice() {
        long currentTime = Instant.now().toEpochMilli();
        return marketPriceRepository.findCurrentMarketPrice(currentTime)
                .map(MarketPrice::getMarketPrice)
                .orElse(null);
    }

    /**
     * Saves the market prices to the repository with adjusted end timestamps to prevent overlaps.
     *
     * @param marketPriceResponse The MarketPriceResponse containing the market price data to save.
     */
    public void saveMarketPrices(MarketPriceResponse marketPriceResponse) {
        List<MarketPrice> marketPrices = marketPriceResponse.getData().stream().map(priceData -> {
            MarketPrice marketPrice = new MarketPrice();
            marketPrice.setStartTimestamp(priceData.getStartTimestamp());
            long adjustedEndTimestamp = priceData.getEndTimestamp() - 1000; // 1 second less to avoid overlap
            if (adjustedEndTimestamp <= priceData.getStartTimestamp()) {
                adjustedEndTimestamp = priceData.getStartTimestamp() + 1000;
            }
            marketPrice.setEndTimestamp(adjustedEndTimestamp);
            marketPrice.setMarketPrice(priceData.getMarketprice());
            marketPrice.setUnit(CENT_KWH);
            return marketPrice;
        }).collect(Collectors.toList());

        marketPriceRepository.saveAll(marketPrices);
        LogFilter.logInfo(MarketPriceService.class, "Saved {} market prices with adjusted end timestamps.", marketPrices.size());
    }
}