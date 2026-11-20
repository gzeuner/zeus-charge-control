package de.zeus.power.service;

import de.zeus.power.config.WebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Exercises the actual Spring 7 HTTP converters against representative provider JSON. */
class HttpClientMigrationTest {
    @Test void operatingModeWritesAreRejectedWithoutContactingTheBattery() {
        var service = new BatteryCommandService(new WebConfig().restTemplateBuilder());
        ReflectionTestUtils.setField(service, "url", "http://battery.test/api/v2");
        ReflectionTestUtils.setField(service, "authToken", "test-token");
        service.init();
        var server = MockRestServiceServer.createServer((RestTemplate) ReflectionTestUtils.getField(service, "restTemplate"));
        for (String mode : java.util.List.of("1", "2", "10", "11")) {
            var response = service.setConfiguration("EM_OperatingMode", mode);
            assertThat(response.success()).isFalse();
            assertThat(response.statusCode().value()).isEqualTo(403);
        }
        assertThat(service.setConfiguration("  em_operatingmode  ", "1").success()).isFalse();
        server.verify();
    }

    @Test void sonnenStatusPreservesCaseSensitiveJsonPropertiesAndAuthentication() {
        var service = new BatteryCommandService(new WebConfig().restTemplateBuilder());
        ReflectionTestUtils.setField(service, "url", "http://battery.test/api/v2");
        ReflectionTestUtils.setField(service, "authToken", "test-token");
        service.init();
        RestTemplate client = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        var server = MockRestServiceServer.createServer(client);
        server.expect(requestTo("http://battery.test/api/v2/status")).andExpect(header("Auth-Token", "test-token"))
                .andRespond(withSuccess("""
                    {"RSOC":64,"RemainingCapacity_Wh":6400,"Production_W":3200,"Consumption_W":1160,
                     "Pac_total_W":2040,"BatteryCharging":true,"BatteryDischarging":false,"OperatingMode":"2",
                     "SystemStatus":"OnGrid","FutureFirmwareField":"ignored"}
                    """, MediaType.APPLICATION_JSON));
        var response = service.getStatus();
        assertThat(response.success()).isTrue();
        assertThat(response.data().getRsoc()).isEqualTo(64);
        assertThat(response.data().getProductionW()).isEqualTo(3200);
        assertThat(response.data().isBatteryCharging()).isTrue();
        server.verify();
    }
    @Test void acLimitPreservesOtherLimitsAndOnlyWritesTheInverterField() {
        var service = new BatteryCommandService(new WebConfig().restTemplateBuilder());
        ReflectionTestUtils.setField(service, "url", "http://battery.test/api/v2");
        ReflectionTestUtils.setField(service, "authToken", "test-token");
        service.init();
        var client = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        var server = MockRestServiceServer.createServer(client);
        String limits = "http://battery.test/api/v2/site/limits";
        server.expect(requestTo(limits)).andRespond(withSuccess("{\"p_gcp_max_import_limit\":200}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(limits)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(limits)).andExpect(method(org.springframework.http.HttpMethod.PUT))
                .andExpect(header("Auth-Token", "test-token"))
                .andExpect(content().json("{\"duration\":\"PT15S\",\"p_bess_inv_max_import_limit\":0}",
                        org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess());
        assertThat(service.limitAcCharging(15, false)).isFalse();
        assertThat(service.limitAcCharging(15, false)).isTrue();
        server.verify();
    }

    @Test void awattarJsonAndEuroPerMWhConversionRemainCompatible() {
        var service = new MarketPriceService(new WebConfig().restTemplateBuilder());
        ReflectionTestUtils.setField(service, "marketDataSource", "awattar");
        ReflectionTestUtils.setField(service, "awattarUrl", "http://prices.test/marketdata");
        ReflectionTestUtils.setField(service, "awattarAuthToken", "");
        RestTemplate client = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        var server = MockRestServiceServer.createServer(client);
        for (int day = 0; day < 2; day++) {
            server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prices.test/marketdata?start=")))
                    .andRespond(withSuccess("""
                        {"object":"list","data":[{"start_timestamp":1792936800000,"end_timestamp":1792940400000,
                         "marketprice":-42.5,"unit":"Eur/MWh"}]}
                        """, MediaType.APPLICATION_JSON));
        }
        var response = service.getMarketPrices();
        assertThat(response.success()).isTrue();
        assertThat(response.data().getData()).hasSize(2);
        assertThat(response.data().getData().getFirst().getMarketprice()).isEqualTo(-4.25);
        assertThat(response.data().getData().getFirst().getStartTimestamp()).isEqualTo(1792936800000L);
        server.verify();
    }
    @Test void batteryUnavailableReturnsFailureInsteadOfThrowing() {
        var service = new BatteryCommandService(new WebConfig().restTemplateBuilder());
        ReflectionTestUtils.setField(service, "url", "http://battery.test/api/v2");
        ReflectionTestUtils.setField(service, "authToken", "test-token");
        service.init();
        var server = MockRestServiceServer.createServer((RestTemplate) ReflectionTestUtils.getField(service, "restTemplate"));
        server.expect(requestTo("http://battery.test/api/v2/status")).andRespond(withServerError());
        assertThat(service.getStatus().success()).isFalse();
        server.verify();
    }
}
