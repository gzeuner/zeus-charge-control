package de.zeus.power.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeatherForecastServiceTest {
    @Test void reusesCompleteForecastThroughBriefOutageAndExpiresStaleData() {
        var weather = new WeatherForecastService();
        var http = mock(RestTemplate.class);
        ReflectionTestUtils.setField(weather, "restTemplate", http);
        ReflectionTestUtils.setField(weather, "baseUrl", "http://forecast.test");
        ReflectionTestUtils.setField(weather, "hourlyParams", "cloudcover");
        ReflectionTestUtils.setField(weather, "lookaheadHours", 2);
        ReflectionTestUtils.setField(weather, "enabled", true);
        long now = Instant.parse("2026-07-01T10:00:00Z").toEpochMilli();
        var times = LongStream.range(0, 6).map(i -> now / 1000 + i * 3600).boxed().toList();
        String json = """
                {"hourly":{"time":%s,"cloud_cover":[10,10,10,10,10,10],"shortwave_radiation":[400,400,400,400,400,400]}}
                """.formatted(times);
        when(http.getForObject(any(URI.class), eq(String.class))).thenReturn(json)
                .thenThrow(new ResourceAccessException("offline"));
        assertEquals(Optional.of(53), weather.previewGridTarget(now, 70, 20, 10000, 4, 350));
        assertEquals(Optional.of(53), weather.previewGridTarget(now + 3_600_000, 70, 20, 10000, 4, 350));
        assertTrue(weather.previewGridTarget(now + 14_400_000, 70, 20, 10000, 4, 350).isEmpty());
    }
}
