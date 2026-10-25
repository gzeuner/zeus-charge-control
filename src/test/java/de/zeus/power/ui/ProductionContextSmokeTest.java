package de.zeus.power.ui;

import de.zeus.power.ClientApplication;
import de.zeus.power.service.DataManagementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Boots the real production wiring, retry proxies, schedulers, HTTP clients and database safely. */
@SpringBootTest(classes = ClientApplication.class, properties = {
        // Existing operator configurations register only the standard messages bundle.
        "spring.messages.basename=messages",
        "battery.url=", "battery.authToken=", "weather.api.enabled=false",
        "scheduled.job.cron=-", "battery.automatic.mode.check.interval=3600000",
        "awattar.marketdata.url=http://127.0.0.1:9/disabled", "tibber.marketdata.url=http://127.0.0.1:9/disabled",
        "spring.datasource.url=jdbc:h2:mem:production-smoke", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.h2.console.enabled=false" })
@AutoConfigureMockMvc
@MockitoBean(types = DataManagementService.class)
class ProductionContextSmokeTest {
    @Autowired MockMvc mvc;

    @ParameterizedTest
    @CsvSource(value = {
            "de|Deine Energie. Bestens geplant.|Nachtruhe|Netzladen starten",
            "en|Energy, beautifully in control.|Quiet hours|Start grid charging"
    }, delimiter = '|')
    void legacyMessageConfigurationStillResolvesAllDashboardLabels(
            String language, String heading, String quietHours, String chargingAction) throws Exception {
        mvc.perform(get("/charging-status").param("lang", language))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(heading)))
                .andExpect(content().string(containsString(quietHours)))
                .andExpect(content().string(containsString(chargingAction)))
                .andExpect(content().string(not(containsString("??"))));
    }

    @Test void productionStartsAndRendersWithoutHardwareAndWithoutTestEndpoints() throws Exception {
        mvc.perform(get("/charging-status?lang=de")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Deine Energie. Bestens geplant.")));
        mvc.perform(get("/current-status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.batteryAvailable").value(false))
                .andExpect(jsonPath("$.batteryNotConfigured").value(true));
        mvc.perform(post("/__test/reset")).andExpect(status().isNotFound());
    }
}
