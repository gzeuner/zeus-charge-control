package de.zeus.power.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DataManagementInitializer {

    @Autowired
    private DataManagementService dataManagementService;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeMarketPricesOnStartup() {
        dataManagementService.updateMarketPrices();
    }
}
