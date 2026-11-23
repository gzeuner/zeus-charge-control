package de.zeus.power.wallbox;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import de.zeus.power.kostal.KostalInverterService;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@EnableConfigurationProperties(WallboxSettings.class)
class WallboxControl {
    private static final Logger LOG = LoggerFactory.getLogger(WallboxControl.class);
    // The charger resets its 10 s watchdog even on a read. Leave a margin for the last lost ACK.
    private static final long RECOVERY_SILENCE = TimeUnit.SECONDS.toNanos(7);
    private static final long MAX_SAMPLE_AGE = TimeUnit.SECONDS.toNanos(8);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("inch-wallbox").factory());
    private final WallboxSettings settings;
    private final WallboxConfigFile config;
    private volatile WallboxPreferences preferences;
    private volatile boolean stopping;
    private record UnconfirmedPause(long retryAfter, String error) {}
    private volatile UnconfirmedPause unconfirmedPause;
    private ScheduledFuture<?> periodic;
    private Sample sample;
    private Instant monitoringSince;
    private ChargerReadings lastReadings;
    private SonnenReadings lastBatteryReadings;
    private Double lastAvailableWatts;
    // Only the single polling thread accesses the policy and session state.
    private WallboxPreferences applied;
    private boolean initialPauseRequired = true;
    private ChargePolicy policy;
    private final InchModbusClient charger;
    private final RestTemplate sonnen;
    private final String statusUrl;
    private Long sessionStart;
    private boolean loggedMeterCheck;
    private final boolean propertyEnabled;
    private boolean featureEnabled;
    private KostalInverterService kostal;

    @Autowired(required = false)
    void configureKostal(KostalInverterService kostal) { this.kostal = kostal; }

    WallboxControl(WallboxSettings settings, RestTemplateBuilder builder,
                   @Value("${battery.url:}") String batteryUrl,
                   @Value("${battery.authToken:}") String token,
                   @Value("${wallbox.enabled:false}") boolean enabled,
                   @Value("${wallbox.config-file:./config/application.properties}") String configFile) throws IOException {
        this.settings = settings;
        this.propertyEnabled = enabled;
        this.featureEnabled = enabled;
        config = new WallboxConfigFile(Path.of(configFile));
        WallboxPreferences loaded = config.read(new WallboxPreferences(enabled, settings.mode(), settings.smartMinUsoc(),
                settings.smartReservePercent()));
        if (enabled && settings.validationError() != null) {
            LOG.error("Wallbox nicht gestartet: {}", settings.validationError());
            this.featureEnabled = false;
        }
        preferences = this.featureEnabled ? loaded : new WallboxPreferences(false, loaded.mode(), loaded.smartMinUsoc(), loaded.smartReservePercent());
        charger = this.featureEnabled ? new InchModbusClient(settings) : null;

        String base = batteryUrl.trim();
        if (base.isEmpty()) {
            base = "";
        }
        statusUrl = base.isEmpty() ? null
                : (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/status";
        if (!token.isBlank()) {
            builder = builder.defaultHeader("Auth-Token", token.trim());
        }
        // Keep an HTTP 503 or Retry-After delay out of the Modbus watchdog cycle.
        sonnen = builder.requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .connectTimeout(Duration.ofMillis(750)).readTimeout(Duration.ofMillis(750)).build();
    }

    @PostConstruct
    synchronized void start() {
        if (!propertyEnabled) LOG.info("Wallbox deaktiviert (property=false)");
        if (preferences.enabled()) startPolling();
    }

    record Sample(ChargerReadings charger, SonnenReadings battery, Float targetAmps, Double availableWatts,
                  boolean connected, String error) {}

    record Status(WallboxPreferences settings, int pollIntervalMs, Sample sample, Instant lastAccess,
                  Instant lastSuccessfulRead, InchModbusClient.ConnectionState modbusState,
                  String modbusError, boolean watchdogPossible, boolean pauseUnconfirmed, boolean featureEnabled) {}

    synchronized Status status() {
        Instant last = charger == null ? null : charger.lastAccess();
        Instant reference = last == null ? monitoringSince : last;
        boolean fallback = reference != null && !Instant.now().isBefore(reference.plusSeconds(10));
        return new Status(preferences, settings.pollIntervalMs(), sample, last,
                charger == null ? null : charger.lastSuccessfulRead(),
                charger == null ? null : charger.connectionState(), charger == null ? null : charger.lastError(),
                fallback, unconfirmedPause != null, featureEnabled);
    }

    synchronized Status update(Map<String, Object> changes) throws IOException {
        if (stopping) throw new IOException("Wallbox controller is shutting down");
        WallboxPreferences next = preferences.update(changes);
        if (!featureEnabled && next.enabled()) throw new IllegalArgumentException("Wallbox-Steuerung ist durch wallbox.enabled=false deaktiviert");
        if (next.equals(preferences)) return status();
        config.save(next);
        boolean wasEnabled = preferences.enabled();
        preferences = next;
        sample = null;
        if (periodic != null) periodic.cancel(false);
        if (next.enabled()) {
            startPolling();
        } else if (wasEnabled) {
            scheduler.execute(this::disable);
        }
        return status();
    }

    private void startPolling() {
        if (monitoringSince == null) monitoringSince = Instant.now();
        periodic = scheduler.scheduleAtFixedRate(this::poll, 0, settings.pollIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void disable() {
        Sample pause;
        try {
            pause = pauseOrWait(null, null);
        } finally {
            charger.close();
            sessionStart = null;
            applied = null;
            initialPauseRequired = true;
        }
        synchronized (this) {
            if (!preferences.enabled()) sample = new Sample(null, null, null, null, false, pause.error());
        }
    }

    // The polling worker is the sole writer of recovery state, including across GUI mode changes.
    private Sample pauseOrWait(SonnenReadings battery, String reason) {
        var pending = unconfirmedPause;
        if (pending != null && System.nanoTime() - pending.retryAfter() < 0) {
            return retainedSample(battery, null, false, pending.error());
        }
        try {
            charger.confirmPause();
            initialPauseRequired = false;
            unconfirmedPause = null;
            if (pending != null) LOG.info("Wallbox confirmed HR 8 = 0 A. Resuming with fresh measurements.");
            return retainedSample(battery, 0f, true, reason);
        } catch (IOException failure) {
            charger.close();
            String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            if (reason != null && !reason.equals(error)) error = reason + " / " + error;
            unconfirmedPause = new UnconfirmedPause(System.nanoTime() + RECOVERY_SILENCE, error);
            LOG.warn("Modbus pause unconfirmed [{}]. Retrying within the charger watchdog window: {}",
                    failure.getClass().getSimpleName(), error);
            return retainedSample(battery, null, false, error);
        }
    }

    private Sample retainedSample(SonnenReadings battery, Float target, boolean connected, String error) {
        return new Sample(lastReadings, battery != null ? battery : lastBatteryReadings, target,
                lastAvailableWatts, connected, error);
    }

    private synchronized void publish(WallboxPreferences active, Sample result) {
        if (preferences == active) sample = result;
    }

    void poll() {
        var active = preferences;
        if (stopping || !active.enabled()) return;
        if (unconfirmedPause != null) {
            publish(active, pauseOrWait(null, null));
            return;
        }
        // A restart may follow a lost write ACK. Establish control before any watchdog-feeding reads.
        if (initialPauseRequired) {
            Sample initial = pauseOrWait(null, null);
            if (unconfirmedPause != null || stopping || active != preferences) {
                publish(active, initial);
                return;
            }
        }
        if (applied != active) {
            policy = new ChargePolicy(settings.withPreferences(active));
            applied = active;
        }
        long started = System.nanoTime();
        SonnenReadings battery = null;
        ChargerReadings ev = null;
        Sample result;
        try {
            battery = readBattery();
            ev = charger.read();
            lastReadings = ev;
            lastBatteryReadings = battery;
            long now = System.nanoTime();
            if (!ev.sessionActive()) {
                sessionStart = null;
            } else if (sessionStart == null) {
                sessionStart = now;
            }
            boolean detecting = sessionStart != null && now - sessionStart < TimeUnit.SECONDS.toNanos(45);
            float target = policy.target(ev, battery, now, kostal == null ? null : kostal.usablePvPowerW());
            LOG.debug("Charger {} lifetime={} kWh on={} EV detection={} Sonnen={}",
                    ev, ev.lifetimeMwh() / 1e6, ev.enabled(), detecting, battery);
            if (ev.selectedMode() != 1) {
                LOG.warn("IR 53={} (erwartet 1). An der Wallbox Power/Fast wählen; sonst kann sie HR 8 überschreiben.",
                        ev.selectedMode());
            }
            if (!loggedMeterCheck && ev.powerKw() > 0 && battery != null && battery.hasPowerReadings()
                    && battery.gridFeedInW() != null) {
                double balanceW = battery.productionW() + battery.batteryW() - battery.gridFeedInW();
                LOG.info("Check meter wiring during stable charging: Consumption_W={} W, site balance={} W, "
                                + "EV={} kW, consumption-includes-wallbox={}.",
                        battery.consumptionW(), balanceW, ev.powerKw(), settings.consumptionIncludesWallbox());
                loggedMeterCheck = true;
            }
            LOG.debug("Mode {} target={} A installed={} A EV max={} A applied={} A",
                    active.mode(), target, ev.installedMaxAmps(), ev.evMaxAmps(), ev.appliedAmps());
            if (stopping || active != preferences) return;
            var write = writeTarget(target, active, started);
            lastAvailableWatts = policy.availableWatts();
            result = new Sample(ev, battery, write.amps(), lastAvailableWatts, true, write.warning());
        } catch (IOException | RuntimeException ex) {
            policy.pause();
            LOG.warn("Wallbox Modbus cycle failed [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
            result = pauseOrWait(battery, ex.toString());
        }
        publish(active, result);
    }

    private SonnenReadings readBattery() {
        if (statusUrl == null) return null;
        try {
            return sonnen.getForObject(statusUrl, SonnenReadings.class);
        } catch (RestClientException | IllegalArgumentException ex) {
            LOG.warn("Wallbox Sonnen status unavailable: {}", ex.getMessage());
            return null;
        }
    }

    private record Write(float amps, String warning) {}

    private void checkPositiveWrite(float amps, WallboxPreferences active, long started) throws IOException {
        if (amps > 0 && (stopping || active != preferences || System.nanoTime() - started >= MAX_SAMPLE_AGE)) {
            throw new IOException("Superseded or expired wallbox measurement");
        }
    }

    private Write writeTarget(float target, WallboxPreferences active, long started) throws IOException {
        checkPositiveWrite(target, active, started);
        try {
            charger.setCurrent(target);
            return new Write(target, charger.recovery());
        } catch (InchModbusClient.DeviceException ex) {
            if (ex.code() != 12 || target == 0) {
                throw ex;
            }
            ChargerReadings fresh = charger.read();
            float max = fresh.maxAmps();
            float retry = fresh.selectedMode() == 1 && fresh.sessionActive() && max < target ? max : 0;
            if (retry == 0) {
                policy.pause();
            }
            LOG.warn("Modbus {} rejected HR 8={} A (slave exception {}). Refreshed ceiling={} A, retry={} A.",
                    ex.getClass().getSimpleName(), target, ex.code(), max, retry);
            checkPositiveWrite(retry, active, started);
            charger.setCurrent(retry);
            return new Write(retry, ex.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        synchronized (this) {
            if (stopping) return;
            stopping = true;
            if (periodic != null) periodic.cancel(false);
            scheduler.execute(featureEnabled && preferences.enabled() ? this::disable : () -> { if (charger != null) charger.close(); });
            scheduler.shutdown();
        }
        // Do not hold the status lock while the worker finishes its final pause.
        try {
            if (!scheduler.awaitTermination(4, TimeUnit.SECONDS)) {
                LOG.warn("Wallbox shutdown deadline expired; the hardware watchdog may take over.");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ex) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
