package de.zeus.power.kostal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class KostalInverterService {
    private static final Logger LOG = LoggerFactory.getLogger(KostalInverterService.class);
    private static final long[] BACKOFF_MS = {2000, 5000, 10000, 30000};
    private final KostalSettings settings;
    private final KostalModbusClientFactory clientFactory;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("kostal-inverter").factory());
    private volatile Snapshot snapshot;
    private KostalModbusTransport client;
    private int failures, backoffIndex;
    private long reconnectAfter;
    private final Set<Integer> disabledRegisters = new HashSet<>();
    private boolean reconnecting;

    public KostalInverterService(KostalSettings settings, KostalModbusClientFactory clientFactory) {
        this.settings = settings;
        this.clientFactory = clientFactory;
    }

    @PostConstruct void start() {
        if (!settings.enabled()) {
            snapshot = new Snapshot("deaktiviert", null, null, null, null, null);
            LOG.info("KOSTAL deaktiviert (property=false)");
            return;
        }
        String invalid = settings.validationError();
        if (invalid != null) {
            snapshot = new Snapshot("Fehlerhafte Konfiguration", null, null, null, null, invalid);
            LOG.error("KOSTAL nicht gestartet: {}", invalid);
            return;
        }
        client = clientFactory.create(settings);
        snapshot = new Snapshot("getrennt", null, null, null, null, null);
        scheduler.scheduleWithFixedDelay(this::safePoll, 0, settings.pollIntervalMs(), TimeUnit.MILLISECONDS);
    }

    public Snapshot status() {
        Snapshot value = snapshot;
        if (value == null || !"verbunden".equals(value.status()) || value.lastValidAt() == null) return value;
        long staleMs = settings.pollIntervalMs() * 3L;
        return Instant.now().isAfter(value.lastValidAt().plusMillis(staleMs))
                ? new Snapshot("stale", value.pvAcPowerW(), value.pvDcPowerW(), value.powermeterW(), value.batterySoc(), value.lastValidAt(), value.error())
                : value;
    }

    public boolean isUsablePv() {
        Snapshot value = status();
        return settings.enabled() && value != null && "KOSTAL".equalsIgnoreCase(settings.pvSource())
                && "verbunden".equals(value.status()) && value.pvAcPowerW() != null;
    }

    public Double usablePvPowerW() { return isUsablePv() ? status().pvAcPowerW() : null; }
    public boolean enabled() { return settings.enabled(); }
    public String pvSource() { return settings.enabled() && "KOSTAL".equalsIgnoreCase(settings.pvSource()) ? "KOSTAL" : "SONNEN"; }

    private void safePoll() {
        try { poll(); }
        catch (Exception ex) { handleFailure(ex); }
    }

    private void poll() throws IOException {
        if (System.currentTimeMillis() < reconnectAfter) return;
        if (client == null) return;
        Double ac = readFloat(172);
        Double dc = settings.readDcPower() ? readFloat(1066) : null;
        Double meter = settings.readPowermeter() ? readFloat(252) : null;
        Double soc = settings.readBattery() ? readFloat(210) : null;
        Instant now = Instant.now();
        snapshot = new Snapshot("verbunden", ac, dc, meter, soc, now, null);
        if (reconnecting) LOG.info("KOSTAL Modbus wieder verbunden");
        reconnecting = false;
        failures = 0; backoffIndex = 0; reconnectAfter = 0;
    }

    private Double readFloat(int register) throws IOException {
        if (disabledRegisters.contains(register)) return null;
        try {
            int[] words = client.readHoldingRegisters(register, 2);
            float result = decodeFloat(words[0], words[1], settings.byteOrder());
            if (!Float.isFinite(result)) throw new IOException("Non-finite value at register " + register);
            if (register == 172 || register == 1066) {
                result = (float) validatePvPower(result, settings.maxPvPowerW());
            }
            return (double) result;
        } catch (KostalModbusClient.ModbusExceptionResponse ex) {
            if (ex.code() == 2) {
                disabledRegisters.add(register);
                LOG.warn("KOSTAL Register {} deaktiviert: Modbus exception 02 (Illegal Data Address)", register);
                return null;
            }
            throw ex;
        }
    }

    static float decodeFloat(int register1, int register2, String byteOrder) {
        byte[] bytes = "ABCD".equalsIgnoreCase(byteOrder)
                ? new byte[]{(byte)(register1 >>> 8), (byte)register1, (byte)(register2 >>> 8), (byte)register2}
                : new byte[]{(byte)(register2 >>> 8), (byte)register2, (byte)(register1 >>> 8), (byte)register1};
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getFloat();
    }

    static double validatePvPower(double watts, double maximumWatts) throws IOException {
        if (!Double.isFinite(watts)) throw new IOException("Non-finite PV power");
        if (watts < -50 || watts > maximumWatts) throw new IOException("Implausible PV power");
        return Math.max(0, watts);
    }

    static long backoffDelayMillis(int retryIndex) {
        return BACKOFF_MS[Math.min(Math.max(retryIndex, 0), BACKOFF_MS.length - 1)];
    }

    private void handleFailure(Exception ex) {
        failures++;
        String kind = ex instanceof SocketTimeoutException ? "SocketTimeoutException" : ex instanceof SocketException ? "SocketException" : ex.getClass().getSimpleName();
        LOG.warn("KOSTAL Modbus-Lesevorgang fehlgeschlagen [{}]: {}", kind, ex.getMessage());
        LOG.debug("KOSTAL Modbus failure details", ex);
        Snapshot old = snapshot;
        snapshot = new Snapshot(old != null && old.lastValidAt() != null
                && Instant.now().isAfter(old.lastValidAt().plusMillis(settings.pollIntervalMs() * 3L)) ? "stale" : "getrennt",
                old == null ? null : old.pvAcPowerW(), old == null ? null : old.pvDcPowerW(), old == null ? null : old.powermeterW(),
                old == null ? null : old.batterySoc(), old == null ? null : old.lastValidAt(), ex.getMessage());
        if (failures >= 3) {
            client.close();
            reconnecting = true;
            reconnectAfter = System.currentTimeMillis() + backoffDelayMillis(backoffIndex);
            backoffIndex = Math.min(backoffIndex + 1, BACKOFF_MS.length - 1);
            failures = 0;
        }
    }

    @PreDestroy void stop() { scheduler.shutdownNow(); if (client != null) client.close(); }

    public record Snapshot(String status, Double pvAcPowerW, Double pvDcPowerW, Double powermeterW,
                           Double batterySoc, Instant lastValidAt, String error) {
        Snapshot(String status, Double ac, Double dc, Double meter, Double soc, String error) { this(status, ac, dc, meter, soc, null, error); }
    }
}
