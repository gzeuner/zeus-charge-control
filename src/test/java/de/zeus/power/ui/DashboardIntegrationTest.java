package de.zeus.power.ui;

import de.zeus.power.entity.*;
import de.zeus.power.repository.*;
import de.zeus.power.service.BatteryManagementService;
import de.zeus.power.util.NightConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = UiTestApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dashboard-test", "spring.jpa.hibernate.ddl-auto=create-drop" })
@AutoConfigureMockMvc
class DashboardIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UiTestApplication.TestState state;
    @Autowired BatteryManagementService battery;
    @Autowired MarketPriceRepository prices;
    @Autowired ChargingScheduleRepository schedules;

    @BeforeEach void reset() { state.reset(); clearInvocations(battery); }

    @Test void rendersGermanDashboardAndEnglishTranslation() throws Exception {
        mvc.perform(get("/charging-status?lang=de")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Deine Energie. Bestens geplant.")))
                .andExpect(content().string(containsString("data-bs-target=\"#market-prices\"")));
        mvc.perform(get("/charging-status?lang=en")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Energy, beautifully in control.")));
        mvc.perform(get("/")).andExpect(redirectedUrl("/charging-status"));
    }
    @Test void servesRealStatusWithPricesAndJackson3Serialization() throws Exception {
        mvc.perform(get("/current-status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.batteryAvailable").value(true))
                .andExpect(jsonPath("$.stateOfCharge").value(64))
                .andExpect(jsonPath("$.remainingCapacityWh").value(6400))
                .andExpect(jsonPath("$.dropRate").doesNotExist())
                .andExpect(jsonPath("$.displayPriceBruttoCt").value(greaterThan(8.42)))
                .andExpect(jsonPath("$.marketPrices", hasSize(30)))
                .andExpect(jsonPath("$.cheapestPeriods", hasSize(5)))
                .andExpect(jsonPath("$.scheduledChargingPeriods", hasSize(3)))
                .andExpect(jsonPath("$.timeZone").isString());
    }
    @Test void missingBatteryDoesNotRenderAsZeroOrBreakTemplate() throws Exception {
        state.available = false;
        mvc.perform(get("/charging-status?lang=de")).andExpect(status().isOk());
        mvc.perform(get("/current-status")).andExpect(jsonPath("$.batteryAvailable").value(false))
                .andExpect(jsonPath("$.stateOfCharge").doesNotExist())
                .andExpect(jsonPath("$.remainingCapacityWh").doesNotExist())
                .andExpect(jsonPath("$.productionW").doesNotExist());
    }
    @ParameterizedTest @ValueSource(strings = {"24", "-1", "1.5", "\"19oops\"", "\"19:30\"", "null", "true", "6"})
    void rejectsInvalidNightWindowWithoutSideEffects(String value) throws Exception {
        mvc.perform(post("/night-charging-window").contentType(MediaType.APPLICATION_JSON)
                .content("{\"startHour\":" + value + ",\"endHour\":6}"))
                .andExpect(jsonPath("$.success").value(false));
        assertThat(NightConfig.getNightStartHour()).isEqualTo(19);
        assertThat(NightConfig.getNightEndHour()).isEqualTo(6);
    }
    @Test void acceptsOvernightAndDaytimeWindows() throws Exception {
        for (String json : new String[]{"{\"startHour\":22,\"endHour\":6}", "{\"startHour\":3,\"endHour\":15}"}) {
            mvc.perform(post("/night-charging-window").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(jsonPath("$.success").value(true));
        }
        assertThat(NightConfig.getNightStartHour()).isEqualTo(3);
    }
    @Test void rejectsMalformedNightToggleBeforeChangingConfiguration() throws Exception {
        mvc.perform(post("/toggle-night-charging").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nightChargingIdle\":true,\"startHour\":25,\"endHour\":6}"))
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/toggle-night-charging").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.success").value(false));
        assertThat(state.nightEnabled).isFalse();
        assertThat(NightConfig.getNightStartHour()).isEqualTo(19);
    }
    @Test void manualModesAndChargingAcknowledgeActualResult() throws Exception {
        postJson("/toggle-mode", "{\"mode\":\"idle\"}", true);
        assertThat(state.paused).isTrue();
        verify(battery).startManualIdleHold();
        postJson("/toggle-charging", "{\"charging\":\"start\"}", true);
        assertThat(state.forced).isTrue(); assertThat(state.paused).isFalse();
        postJson("/toggle-charging", "{\"charging\":\"stop\"}", true);
        assertThat(state.forced).isFalse();
        postJson("/toggle-mode", "{\"mode\":\"standard\"}", true);
        verify(battery, atLeastOnce()).resetToAutomaticMode(true);
    }
    @Test void failedPauseDoesNotClearActiveChargeFlag() throws Exception {
        state.forced = true; state.reject = true;
        postJson("/toggle-mode", "{\"mode\":\"idle\"}", false);
        assertThat(state.forced).isTrue(); assertThat(state.paused).isFalse();
    }
    @Test void failedResetDoesNotReportAutomaticMode() throws Exception {
        state.paused = true; state.reject = true;
        postJson("/reset-automatic", "{}", false);
        assertThat(state.paused).isTrue();
        postJson("/reset-idle", "{}", false);
        verify(battery, never()).setManualIdleActive(anyBoolean());
    }
    @Test void failedNightHandbackIsReported() throws Exception {
        state.nightEnabled = true; state.reject = true;
        postJson("/toggle-night-charging", "{\"nightChargingIdle\":false}", false);
    }
    @Test void invalidActionsCannotTriggerHardwareCommands() throws Exception {
        postJson("/toggle-mode", "{\"mode\":\"unknown\"}", false);
        postJson("/toggle-charging", "{\"charging\":\"unknown\"}", false);
        verifyNoInteractions(battery);
    }
    @Test @Transactional void hibernateAndH2PersistPricesAndChargingSchedules() {
        MarketPrice unsavedPrice = state.prices().getFirst();
        unsavedPrice.setId(null);
        MarketPrice price = prices.saveAndFlush(unsavedPrice);
        ChargingSchedule schedule = schedules.saveAndFlush(state.schedules().getFirst());
        assertThat(prices.findById(price.getId())).isPresent();
        assertThat(schedules.findById(schedule.getId())).isPresent();
    }
    @Test void licensePagesAndVendorAssetsAreServed() throws Exception {
        for (String path : new String[]{"/license?lang=de", "/license?lang=en", "/css/darkmode.css", "/js/main.js", "/js/chart.min.js"})
            mvc.perform(get(path)).andExpect(status().isOk());
    }
    private void postJson(String path, String body, boolean success) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(success));
    }
}
