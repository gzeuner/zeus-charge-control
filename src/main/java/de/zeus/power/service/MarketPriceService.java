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
 * Copyright 2025 Guido Zeuner - https://tiny-tool.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 *
 * Fetches and persists market prices from configured providers (Awattar/Tibber),
 * including fallback logic and lightweight validation.
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

    @Value("${marketdata.source:awattar}")
    private String marketDataSource;

    @Value("${awattar.marketdata.url:https://api.awattar.de/v1/marketdata}")
    private String awattarUrl;

    @Value("${awattar.authToken:}")
    private String awattarAuthToken;

    @Value("${tibber.marketdata.url:https://api.tibber.com/v1-beta/gql}")
    private String tibberUrl;

    @Value("${tibber.authToken:}")
    private String tibberAuthToken;

    // Default queries if not provided in properties
    @Value("${tibber.query.today:{\"query\":\"{viewer {homes {currentSubscription {priceInfo {today {energy startsAt}}}}}}\"}}")
    private String tibberQueryToday;

    @Value("${tibber.query.tomorrow:{\"query\":\"{viewer {homes {currentSubscription {priceInfo {tomorrow {energy startsAt}}}}}}\"}}")
    private String tibberQueryTomorrow;

    @Value("${battery.accepted.delay:30}")
    private int acceptedDelayInMinutes;

    private final RestTemplate restTemplate;

    public MarketPriceService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }

    /**
     * Primary source per config (tibber/awattar) with fallback logic:
     * - If Tibber is configured but fails/returns empty -> fall back to Awattar
     * - If Awattar is primary -> use Awattar only
     */
    public ApiResponse<MarketPriceResponse> getMarketPrices() {
        ApiResponse<MarketPriceResponse> response = null;
        String source = marketDataSource == null ? "" : marketDataSource.trim().toLowerCase();

        if (TIBBER.equals(source)) {
            logger.info("Fetching market prices from Tibber (primary)...");
            if (!isTibberConfigured()) {
                logger.warn("Tibber not fully configured (url/token/query). Falling back to Awattar...");
            } else {
                response = safeGetTibberPrices();
                if (isValid(response)) {
                    logger.info("Successfully retrieved market prices from Tibber.");
                    return response;
                }
                logger.warn("Tibber returned no/invalid data ({}). Falling back to Awattar...",
                        response == null ? "null" : response.statusCode());
            }
            // Fallback -> Awattar
            response = safeGetAwattarPrices();
            if (isValid(response)) {
                logger.info("Successfully retrieved market prices from Awattar (fallback).");
                return response;
            }
            logger.error("Fallback to Awattar failed ({}). No valid prices available.",
                    response == null ? "null" : response.statusCode());
            return failAll();
        }

        // Default or explicit Awattar
        logger.info("Fetching market prices from Awattar (primary)...");
        response = safeGetAwattarPrices();
        if (isValid(response)) {
            logger.info("Successfully retrieved market prices from Awattar.");
            return response;
        }
        // Optional: try Tibber as a second fallback
        logger.warn("Awattar failed ({}). Trying Tibber as secondary source...",
                response == null ? "null" : response.statusCode());
        if (isTibberConfigured()) {
            ApiResponse<MarketPriceResponse> tibber = safeGetTibberPrices();
            if (isValid(tibber)) {
                logger.info("Successfully retrieved market prices from Tibber (secondary).");
                return tibber;
            }
        } else {
            logger.warn("Tibber not configured; skipping secondary attempt.");
        }

        logger.error("No valid prices available from any source.");
        return failAll();
    }

    private boolean isValid(ApiResponse<MarketPriceResponse> resp) {
        return resp != null
                && resp.success()
                && resp.data() != null
                && resp.data().getData() != null
                && !resp.data().getData().isEmpty();
    }

    private boolean isTibberConfigured() {
        return notEmpty(tibberUrl) && notEmpty(tibberAuthToken) && notEmpty(tibberQueryToday);
    }

    private boolean notEmpty(String s) {
        return s != null && !s.isBlank();
    }

    private ApiResponse<MarketPriceResponse> failAll() {
        return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to retrieve market prices from all sources", null);
    }

    private ApiResponse<MarketPriceResponse> safeGetAwattarPrices() {
        try {
            return getAwattarPrices();
        } catch (Exception ex) {
            logger.error("Awattar fetch threw exception (no retry here): {}", ex.getMessage(), ex);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), null);
        }
    }

    private ApiResponse<MarketPriceResponse> safeGetTibberPrices() {
        try {
            return getTibberPrices();
        } catch (Exception ex) {
            logger.error("Tibber fetch threw exception (no retry here): {}", ex.getMessage(), ex);
            return new ApiResponse<>(false, HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), null);
        }
    }

    // ---------- Awattar ----------

    @Retryable(value = { RestClientException.class }, maxAttempts = 3, backoff = @Backoff(delay = 2000))
    private ApiResponse<MarketPriceResponse> getAwattarPrices() {
        try {
            long todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000;
            long tomorrowStart = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000;

            String urlToday = awattarUrl + "?start=" + todayStart;
            String urlTomorrow = awattarUrl + "?start=" + tomorrowStart;

            ResponseEntity<MarketPriceResponse> responseToday = fetchAwattarPrices(urlToday);
            ResponseEntity<MarketPriceResponse> responseTomorrow = fetchAwattarPrices(urlTomorrow);

            if (responseToday.getStatusCode().is2xxSuccessful()
                    && responseTomorrow.getStatusCode().is2xxSuccessful()
                    && responseToday.getBody() != null
                    && responseTomorrow.getBody() != null) {
                MarketPriceResponse combined = combineAwattarResponses(responseToday.getBody(), responseTomorrow.getBody());
                return new ApiResponse<>(true, HttpStatus.OK, "Market prices retrieved successfully from Awattar", combined);
            }
            return new ApiResponse<>(false, HttpStatus.BAD_GATEWAY, "Failed to retrieve complete market prices from Awattar", null);
        } catch (RestClientException e) {
            logger.warn("Transient error fetching Awattar prices, retrying... Error: {}", e.getMessage());
            throw e; // trigger retry
        } catch (Exception e) {
            return handleException(e);
        }
    }

    private ResponseEntity<MarketPriceResponse> fetchAwattarPrices(String url) {
        HttpHeaders headers = createHeaders(awattarAuthToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<String> request = new HttpEntity<>(headers);
        return restTemplate.exchange(url, HttpMethod.GET, request, MarketPriceResponse.class);
    }

    private MarketPriceResponse combineAwattarResponses(MarketPriceResponse today, MarketPriceResponse tomorrow) {
        today.getData().addAll(tomorrow.getData());
        return convertAwattarToCentPerKWh(today);
    }

    // ---------- Tibber ----------

    @Retryable(value = { RestClientException.class }, maxAttempts = 3, backoff = @Backoff(delay = 2000))
    private ApiResponse<MarketPriceResponse> getTibberPrices() {
        try {
            ResponseEntity<TibberResponse> responseToday = fetchTibberPrices(tibberQueryToday);
            ResponseEntity<TibberResponse> responseTomorrow = fetchTibberPrices(tibberQueryTomorrow);

            boolean hasTomorrowData = responseTomorrow.getBody() != null
                    && responseTomorrow.getBody().getData() != null
                    && !responseTomorrow.getBody().getData().getViewer().getHomes().get(0)
                    .getCurrentSubscription().getPriceInfo().getTomorrow().isEmpty();

            if (responseToday.getStatusCode().is2xxSuccessful() && responseToday.getBody() != null) {
                MarketPriceResponse mapped = mapTibberToMarketPriceResponse(
                        responseToday.getBody(), hasTomorrowData ? responseTomorrow.getBody() : null);

                // If Tibber has no prices for tomorrow -> targeted fallback only for tomorrow
                if (!hasTomorrowData) {
                    logger.warn("Tibber has no prices for tomorrow. Trying to fill from Awattar...");
                    ApiResponse<MarketPriceResponse> awattar = safeGetAwattarPrices();
                    if (isValid(awattar)) {
                        mapped.getData().addAll(
                                awattar.data().getData().stream()
                                        .filter(d -> Instant.ofEpochMilli(d.getStartTimestamp())
                                                .atZone(ZoneId.systemDefault()).toLocalDate()
                                                .isEqual(LocalDate.now().plusDays(1)))
                                        .collect(Collectors.toList())
                        );
                        logger.info("Successfully filled missing tomorrow prices from Awattar.");
                    } else {
                        logger.error("Awattar fallback for tomorrow failed: {}", awattar.message());
                    }
                }

                return new ApiResponse<>(true, HttpStatus.OK, "Market prices retrieved successfully from Tibber", mapped);
            }
            return new ApiResponse<>(false, HttpStatus.BAD_GATEWAY, "Failed to retrieve market prices from Tibber", null);
        } catch (RestClientException e) {
            logger.warn("Transient error fetching Tibber prices, retrying... Error: {}", e.getMessage());
            throw e; // trigger retry
        } catch (Exception e) {
            return handleException(e);
        }
    }

    private ResponseEntity<TibberResponse> fetchTibberPrices(String query) {
        HttpHeaders headers = createHeaders(tibberAuthToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<String> request = new HttpEntity<>(query, headers);
        return restTemplate.postForEntity(tibberUrl, request, TibberResponse.class);
    }

    private HttpHeaders createHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

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
            logger.error("HTTP error while retrieving market prices: {} (Status: {})", message, status, e);
        } else {
            logger.error("Unexpected error while retrieving market prices", e);
        }
        return new ApiResponse<>(false, status, message, null);
    }

    private MarketPriceResponse convertAwattarToCentPerKWh(MarketPriceResponse response) {
        response.getData().forEach(d -> {
            // Awattar returns EUR/MWh -> (EUR/MWh)/10 = ct/kWh (1 MWh = 1000 kWh; EUR*100 -> cents)
            d.setMarketprice(Math.round((d.getMarketprice() / 10.0) * CONVERSION_FACTOR) / CONVERSION_FACTOR);
            d.setUnit(CENT_KWH);
        });
        return response;
    }

    private MarketPriceResponse mapTibberToMarketPriceResponse(TibberResponse today, TibberResponse tomorrow) {
        List<MarketPriceResponse.MarketData> list =
                mapTibberPriceData(today.getData().getViewer().getHomes().get(0)
                        .getCurrentSubscription().getPriceInfo().getToday());

        if (tomorrow != null && tomorrow.getData() != null) {
            list.addAll(mapTibberPriceData(tomorrow.getData().getViewer().getHomes().get(0)
                    .getCurrentSubscription().getPriceInfo().getTomorrow()));
        } else {
            LogFilter.logWarn(MarketPriceService.class, "No 'tomorrow' data in Tibber response.");
        }

        MarketPriceResponse res = new MarketPriceResponse();
        res.setObject("list");
        res.setData(list);
        return res;
    }

    private List<MarketPriceResponse.MarketData> mapTibberPriceData(List<TibberResponse.PriceData> priceDataList) {
        return priceDataList.stream().map(data -> {
            MarketPriceResponse.MarketData md = new MarketPriceResponse.MarketData();
            md.setStartTimestamp(Instant.parse(data.getStartsAt()).toEpochMilli());
            md.setEndTimestamp(Instant.parse(data.getStartsAt()).plus(1, ChronoUnit.HOURS).toEpochMilli());
            // Tibber returns EUR/kWh -> *100 = ct/kWh
            md.setMarketprice(Math.round((data.getEnergy() * CONVERSION_FACTOR) * CONVERSION_FACTOR) / CONVERSION_FACTOR);
            md.setUnit(CENT_KWH);
            return md;
        }).collect(Collectors.toList());
    }

    public List<MarketPrice> getAllMarketPrices() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        long currentTime = now.atZone(ZoneId.systemDefault()).toEpochSecond() * 1000;
        long pastTime = now.minusMinutes(acceptedDelayInMinutes).atZone(ZoneId.systemDefault()).toEpochSecond() * 1000;
        return marketPriceRepository.findValidMarketPrices(currentTime, pastTime);
    }

    public Double getCurrentPriceForTimeRange() {
        long currentTime = Instant.now().toEpochMilli();
        List<MarketPrice> prices = marketPriceRepository.findValidMarketPrices(
                currentTime, Instant.now().minus(acceptedDelayInMinutes, ChronoUnit.MINUTES).toEpochMilli());
        return prices.stream()
                .filter(p -> p.getStartTimestamp() <= currentTime && p.getEndTimestamp() > currentTime)
                .map(MarketPrice::getMarketPrice)
                .findFirst()
                .orElse(null);
    }

    public Double getCurrentlyValidPrice() {
        long currentTime = Instant.now().toEpochMilli();
        return marketPriceRepository.findCurrentMarketPrice(currentTime)
                .map(MarketPrice::getMarketPrice)
                .orElse(null);
    }

    public void saveMarketPrices(MarketPriceResponse marketPriceResponse) {
        List<MarketPrice> marketPrices = marketPriceResponse.getData().stream().map(priceData -> {
            MarketPrice mp = new MarketPrice();
            mp.setStartTimestamp(priceData.getStartTimestamp());
            long adjustedEnd = priceData.getEndTimestamp() - 1000; // subtract 1s to avoid overlap
            if (adjustedEnd <= priceData.getStartTimestamp()) {
                adjustedEnd = priceData.getStartTimestamp() + 1000;
            }
            mp.setEndTimestamp(adjustedEnd);
            mp.setMarketPrice(priceData.getMarketprice());
            mp.setUnit(CENT_KWH);
            return mp;
        }).collect(Collectors.toList());

        marketPriceRepository.saveAll(marketPrices);
        LogFilter.logInfo(MarketPriceService.class, "Saved {} market prices with adjusted end timestamps.", marketPrices.size());
    }
}
