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
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

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
 *
 */

@Service
public class MarketPriceService {

    /**
     * Logger instance for logging events in MarketPriceService.
     */
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
     * Retrieves market prices from the configured data source, with a fallback mechanism.
     * <p>
     * The method attempts to fetch market prices from the primary data source specified by
     * {@code marketDataSource}. If the primary source is "tibber", it tries to retrieve prices
     * from Tibber. If the retrieval is unsuccessful or the data is invalid, it falls back to
     * Awattar. If the primary source is "awattar", it directly fetches prices from Awattar.
     * <p>
     * The method returns an {@link ApiResponse} containing a {@link MarketPriceResponse} with
     * the market price data or an error message if both sources fail.
     *
     * @return an {@link ApiResponse} containing the market price data or an error message.
     */
    public ApiResponse<MarketPriceResponse> getMarketPrices() {
        MarketPriceResponse marketPriceResponse = null;

        // Try Tibber first
        if (TIBBER.equalsIgnoreCase(marketDataSource)) {
            logger.info("Fetching market prices from Tibber...");
            ApiResponse<MarketPriceResponse> tibberResponse = getTibberPrices();

            if (tibberResponse.data() != null && !tibberResponse.data().getData().isEmpty()) {
                logger.info("Successfully retrieved market prices from Tibber.");
                return tibberResponse;
            } else {
                logger.warn("Failed to retrieve valid data from Tibber. Falling back to Awattar...");
            }
        }

        // Try Awattar if Tibber fails or is not configured as the primary source
        if (AWATTAR.equalsIgnoreCase(marketDataSource) || marketPriceResponse == null) {
            logger.info("Fetching market prices from Awattar...");
            ApiResponse<MarketPriceResponse> awattarResponse = getAwattarPrices();

            if (awattarResponse.data() != null && !awattarResponse.data().getData().isEmpty()) {
                logger.info("Successfully retrieved market prices from Awattar.");
                return awattarResponse;
            } else {
                logger.error("Failed to retrieve valid data from both Tibber and Awattar.");
            }
        }

        // If all sources fail
        return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "No valid market prices retrieved", null);
    }

    /**
     * Retrieves market prices from Awattar and combines today's and tomorrow's data.
     *
     * @return ApiResponse containing the combined market price data or an error message.
     */
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
                return new ApiResponse<>(true, HttpStatus.OK, "Market prices retrieved successfully", combinedResponse);
            } else {
                return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to retrieve market prices from Awattar", null);
            }
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
     * Retrieves market prices from Tibber for today and tomorrow.
     *
     * @return ApiResponse containing the combined market price data or an error message.
     */
    private ApiResponse<MarketPriceResponse> getTibberPrices() {
        try {
            ResponseEntity<TibberResponse> responseToday = fetchTibberPrices(tibberQueryToday);
            ResponseEntity<TibberResponse> responseTomorrow = fetchTibberPrices(tibberQueryTomorrow);

            boolean hasTomorrowData = responseTomorrow.getBody() != null &&
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
                        logger.error("Failed to retrieve fallback prices from Awattar.");
                    }
                }

                return new ApiResponse<>(true, HttpStatus.OK, "Market prices retrieved successfully", marketPriceResponse);
            } else {
                return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to retrieve market prices from Tibber", null);
            }
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
     * Handles exceptions that occur during the retrieval of market prices.
     *
     * @param e The exception that occurred.
     * @return ApiResponse with an error message and status.
     */
    private ApiResponse<MarketPriceResponse> handleException(Exception e) {
        if (e instanceof HttpClientErrorException || e instanceof HttpServerErrorException) {
            HttpStatus status = ((HttpClientErrorException) e).getStatusCode();
            logger.error("Exception occurred while retrieving market prices", e);
            return new ApiResponse<>(false, status, "Error: " + ((HttpClientErrorException) e).getStatusText(), null);
        } else {
            logger.error("An error occurred while retrieving market prices", e);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", null);
        }
    }

    /**
     * Converts the market price data from Awattar to cent/kWh and rounds to 2 decimal places.
     *
     * @param response The MarketPriceResponse containing Awattar market price data.
     * @return MarketPriceResponse with converted market price data.
     */
    private MarketPriceResponse convertAwattarToCentPerKWh(MarketPriceResponse response) {
        response.getData().forEach(data -> {
            data.setMarketprice(Math.round((data.getMarketprice() / 10) * CONVERSION_FACTOR) / CONVERSION_FACTOR); // Convert Eur/MWh to cent/kWh and round to 2 decimals
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
            LogFilter.log(LogFilter.LOG_LEVEL_WARN, "No data available for tomorrow in Tibber response.");
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
            marketData.setMarketprice(Math.round((data.getEnergy() * CONVERSION_FACTOR) * CONVERSION_FACTOR) / CONVERSION_FACTOR); // Convert Eur/kWh to cent/kWh and round to 2 decimals
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
     * Saves the market prices to the repository.
     *
     * This method processes the market price data, adjusts the end timestamps to prevent overlaps,
     * and saves the resulting MarketPrice objects to the repository.
     *
     * @param marketPriceResponse The MarketPriceResponse containing the market price data to save.
     */
    public void saveMarketPrices(MarketPriceResponse marketPriceResponse) {
        List<MarketPrice> marketPrices = marketPriceResponse.getData().stream().map(priceData -> {
            MarketPrice marketPrice = new MarketPrice();
            marketPrice.setStartTimestamp(priceData.getStartTimestamp());

            // Adjust the end timestamp to prevent overlaps by subtracting 1 second
            long adjustedEndTimestamp = priceData.getEndTimestamp() - 1000; // 1000 ms = 1 second
            if (adjustedEndTimestamp <= priceData.getStartTimestamp()) {
                // Ensure the end timestamp is always after the start timestamp
                adjustedEndTimestamp = priceData.getStartTimestamp() + 1000;
            }
            marketPrice.setEndTimestamp(adjustedEndTimestamp);

            // Set the market price and unit
            marketPrice.setMarketPrice(priceData.getMarketprice());
            marketPrice.setUnit(CENT_KWH);
            return marketPrice;
        }).collect(Collectors.toList());

        // Save all adjusted market prices to the repository
        marketPriceRepository.saveAll(marketPrices);

        // Log the operation
        LogFilter.log(
                LogFilter.LOG_LEVEL_INFO,
                String.format("Saved %d market prices with adjusted end timestamps.", marketPrices.size())
        );
    }

}
