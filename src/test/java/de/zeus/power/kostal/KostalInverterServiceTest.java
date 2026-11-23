package de.zeus.power.kostal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class KostalInverterServiceTest {
    @Test void disabledOrInvalidConfigurationNeverCreatesModbusClient() {
        KostalModbusClientFactory factory = mock(KostalModbusClientFactory.class);
        KostalInverterService disabled = new KostalInverterService(settings(false, "", "SONNEN"), factory);
        disabled.start();
        assertEquals("deaktiviert", disabled.status().status());
        verifyNoInteractions(factory);
        disabled.stop();

        KostalInverterService invalid = new KostalInverterService(settings(true, "<INVERTER_IP>", "SONNEN"), factory);
        invalid.start();
        assertEquals("Fehlerhafte Konfiguration", invalid.status().status());
        verifyNoInteractions(factory);
        invalid.stop();
    }

    @Test void enabledAndValidConfigurationCreatesTheClientAndStartsPolling() throws Exception {
        KostalModbusClientFactory factory = mock(KostalModbusClientFactory.class);
        KostalSettings settings = settings(true, "localhost", "KOSTAL");
        KostalModbusTransport transport = mock(KostalModbusTransport.class);
        when(factory.create(settings)).thenReturn(transport);
        when(transport.readHoldingRegisters(172, 2)).thenReturn(words(4962.6f));
        KostalInverterService service = new KostalInverterService(settings, factory);
        try {
            service.start();
            verify(factory).create(settings);
            await().atMost(2, TimeUnit.SECONDS).until(service::isUsablePv);
            assertEquals(4962.6, service.usablePvPowerW(), 0.01);
        } finally {
            service.stop();
        }
    }

    @Test void timeoutRetainsLastValidValueButMarksItStaleAndNotUsable() throws Exception {
        KostalModbusClientFactory factory = mock(KostalModbusClientFactory.class);
        KostalSettings settings = settings(true, "localhost", "KOSTAL");
        KostalModbusTransport transport = mock(KostalModbusTransport.class);
        when(factory.create(settings)).thenReturn(transport);
        when(transport.readHoldingRegisters(172, 2))
                .thenReturn(words(4814f))
                .thenThrow(new SocketTimeoutException("read timed out"));
        KostalInverterService service = new KostalInverterService(settings, factory);
        try {
            service.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> "stale".equals(service.status().status()));
            assertEquals(4814.0, service.status().pvAcPowerW(), 0.01);
            assertNotNull(service.status().lastValidAt());
            assertFalse(service.isUsablePv());
            verify(transport).close();
        } finally {
            service.stop();
        }
    }

    @Test void illegalAddressDisablesOnlyThatRegisterWhileSlaveBusyCountsAsFailure() throws Exception {
        KostalModbusClientFactory factory = mock(KostalModbusClientFactory.class);
        KostalSettings settings = settings(true, "localhost", "KOSTAL");
        KostalModbusTransport transport = mock(KostalModbusTransport.class);
        when(factory.create(settings)).thenReturn(transport);
        when(transport.readHoldingRegisters(172, 2))
                .thenThrow(new KostalModbusClient.ModbusExceptionResponse(2));
        KostalInverterService service = new KostalInverterService(settings, factory);
        try {
            service.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> "verbunden".equals(service.status().status()));
            Thread.sleep(300);
            assertNull(service.status().pvAcPowerW());
            verify(transport, times(1)).readHoldingRegisters(172, 2);
            verify(transport, never()).close();
        } finally {
            service.stop();
        }

        KostalModbusClientFactory retryFactory = mock(KostalModbusClientFactory.class);
        KostalModbusTransport retryTransport = mock(KostalModbusTransport.class);
        when(retryFactory.create(settings)).thenReturn(retryTransport);
        when(retryTransport.readHoldingRegisters(172, 2))
                .thenThrow(new KostalModbusClient.ModbusExceptionResponse(6))
                .thenThrow(new SocketException("connection reset"))
                .thenThrow(new SocketTimeoutException("timeout"))
                .thenReturn(words(1200f));
        KostalInverterService retryService = new KostalInverterService(settings, retryFactory);
        try {
            retryService.start();
            await().atMost(5, TimeUnit.SECONDS).until(retryService::isUsablePv);
            assertEquals(1200, retryService.usablePvPowerW(), 0.01);
            verify(retryTransport).close();
            verify(retryTransport, times(4)).readHoldingRegisters(172, 2);
        } finally {
            retryService.stop();
        }
    }

    private KostalSettings settings(boolean enabled, String host, String source) {
        return new KostalSettings(enabled, host, 1502, 71, "CDAB", 120,
                2000, 50, false, false, false, source, 30000);
    }

    private int[] words(float value) {
        int bits = Float.floatToIntBits(value);
        return new int[]{bits & 0xffff, bits >>> 16};
    }
}
