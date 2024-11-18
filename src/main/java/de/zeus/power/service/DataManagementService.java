package de.zeus.power.service;

import de.zeus.power.entity.MarketPrice;
import de.zeus.power.event.MarketPricesUpdatedEvent;
import de.zeus.power.model.ApiResponse;
import de.zeus.power.model.MarketPriceResponse;
import de.zeus.power.repository.MarketPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;

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
 */


@Service
public class DataManagementService {

    /**
     * Logger instance for logging events in DataManagementService.
     */
    private static final Logger logger = LoggerFactory.getLogger(DataManagementService.class);

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private MarketPriceService marketPriceService;

    @Autowired
    private MarketPriceRepository marketPriceRepository;

    @Value("${marketdata.print:false}")
    private boolean printMarketData;

    /**
     * Updates the market prices by fetching new data from the MarketPriceService and
     * saving it to the repository. If the update is successful, schedules the charging
     * process. Optionally prints all market prices if 'printMarketData' is enabled.
     */
    @PostConstruct
    @Scheduled(cron = "${scheduled.job.cron:0 15 14 * * *}")
    public void updateMarketPrices() {
        ApiResponse<MarketPriceResponse> response = marketPriceService.getMarketPrices();
        if (response.success()) {
            marketPriceRepository.deleteAllInBatch();
            marketPriceService.saveMarketPrices(response.data());
            eventPublisher.publishEvent(new MarketPricesUpdatedEvent(this));
            logger.info("Market prices updated successfully.");
        } else {
            logger.error("Failed to update market prices: " + response.message());
        }

        if (printMarketData) {
            printAllMarketPrices();
        }
    }

    /**
     * Prints all market prices to the log. Each market price entry includes the start
     * and end timestamps and the price in cent/kWh.
     */
    public void printAllMarketPrices() {
        List<MarketPrice> allMarketPrices = marketPriceRepository.findAll();
        if (allMarketPrices.isEmpty()) {
            logger.info("No market prices found.");
        } else {
            allMarketPrices.forEach(marketPrice -> logger.info(String.format("Market Price: StartTimestamp: %s, EndTimestamp: %s, MarketPrice: %.2f cent/kWh",
                    marketPrice.getFormattedStartTimestamp(), marketPrice.getFormattedEndTimestamp(),
                    marketPrice.getPriceInCentPerKWh())));
        }
    }
}
