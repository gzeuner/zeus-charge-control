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
import org.springframework.core.env.Environment;

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
    private enum PauseStrategy { PAUSE_COMMAND, MIN_CURRENT }
    // The charger watchdog is 35 s; pause recovery leaves a margin for a lost ACK.
    private static final long RECOVERY_SILENCE = TimeUnit.SECONDS.toNanos(7);
    private static final long MAX_SAMPLE_AGE = TimeUnit.SECONDS.toNanos(8);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("inch-wallbox").factory());
    private final WallboxSettings settings;
    private final PauseStrategy pauseStrategy;
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
    private volatile WallboxSettings.Mode activeMode;
    private volatile String modeSource = "config";
    private volatile Float lastWrittenTargetAmps;
    private Instant lastReadbackLog;
    private Instant lastDeviationLog;
    private int consecutiveTargetDeviations;
    private int consecutivePauseWithCurrent;
    private boolean pauseCommandActive;
    private Instant lastTargetLog;
    private Float lastLoggedTargetAmps;
    private WallboxSettings.Mode lastLoggedMode;
    private boolean initialPauseRequired = true;
    private ChargePolicy policy;
    private final InchModbusClient charger;
    private final RestTemplate sonnen;
    private final String statusUrl;
    private Long sessionStart;
    private boolean loggedMeterCheck;
    private Instant lastIr53Info;
    private final boolean propertyEnabled;
    private boolean featureEnabled;
    private KostalInverterService kostal;
    @Value("${battery.inverter.max.watts:4600}")
    private double inverterMaxWatts;

    @Autowired(required = false)
    void configureKostal(KostalInverterService kostal) { this.kostal = kostal; }

    WallboxControl(WallboxSettings settings, RestTemplateBuilder builder,
                   @Value("${battery.url:}") String batteryUrl,
                   @Value("${battery.authToken:}") String token,
                   @Value("${wallbox.enabled:false}") boolean enabled,
                   @Value("${wallbox.config-file:./config/application.properties}") String configFile,
                   Environment environment,
                   @Value("${wallbox.pause-strategy:PAUSE_COMMAND}") String configuredPauseStrategy) throws IOException {
        this.settings = settings;
        this.propertyEnabled = enabled;
        this.featureEnabled = enabled;
        PauseStrategy parsedPauseStrategy;
        try {
            parsedPauseStrategy = PauseStrategy.valueOf(
                    configuredPauseStrategy.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException ex) {
            LOG.warn("Invalid wallbox.pause-strategy {}; using PAUSE_COMMAND", configuredPauseStrategy);
            parsedPauseStrategy = PauseStrategy.PAUSE_COMMAND;
        }
        this.pauseStrategy = parsedPauseStrategy;
        config = new WallboxConfigFile(Path.of(configFile));
        String environmentMode = environment.getProperty("WALLBOX_MODE");
        var configuredMode = WallboxModeResolver.resolve(null, environmentMode, settings.mode());
        WallboxPreferences defaults = new WallboxPreferences(enabled, configuredMode.mode(), settings.smartMinUsoc(),
                settings.smartReservePercent());
        WallboxConfigFile.Loaded loaded = config.read(defaults);
        var resolution = loaded.hasGuiBlock()
                ? WallboxModeResolver.resolve(loaded.persistedMode(), environmentMode, settings.mode())
                : configuredMode;
        preferences = loaded.preferences().mode() == resolution.mode() ? loaded.preferences()
                : new WallboxPreferences(loaded.preferences().enabled(), resolution.mode(),
                        loaded.preferences().smartMinUsoc(), loaded.preferences().smartReservePercent());
        modeSource = resolution.source();
        if (enabled && settings.validationError() != null) {
            LOG.error("Wallbox nicht gestartet: {}", settings.validationError());
            this.featureEnabled = false;
        }
        preferences = this.featureEnabled ? preferences : new WallboxPreferences(false, preferences.mode(),
                preferences.smartMinUsoc(), preferences.smartReservePercent());
        charger = this.featureEnabled ? new InchModbusClient(settings) : null;
        LOG.info("Effective wallbox settings: mode={} source={} minimum={}A maximum={}A gridLimit={}W phases={} voltage={}V pauseStrategy={}",
                preferences.mode(), modeSource, settings.minCurrentA(), settings.maxCurrentA(),
                settings.gridLimitWatts(), settings.phases(), settings.voltage(), pauseStrategy);

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

    record Status(WallboxPreferences settings, WallboxSettings.Mode activeMode, String modeSource,
                  Float lastWrittenTargetAmps, Float boxReportedAppliedAmps, int pollIntervalMs, Sample sample, Instant lastAccess,
                  Instant lastSuccessfulRead, InchModbusClient.ConnectionState modbusState,
                  String modbusError, boolean watchdogPossible, boolean pauseUnconfirmed, boolean featureEnabled) {}

    synchronized Status status() {
        Instant last = charger == null ? null : charger.lastAccess();
        Instant reference = last == null ? monitoringSince : last;
        boolean fallback = reference != null && !Instant.now().isBefore(reference.plusSeconds(35));
        ChargerReadings readings = sample != null && sample.charger() != null ? sample.charger() : lastReadings;
        return new Status(preferences, activeMode, modeSource, lastWrittenTargetAmps,
                readings == null ? null : readings.appliedAmps(), settings.pollIntervalMs(), sample, last,
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
        WallboxSettings.Mode oldMode = preferences.mode();
        modeSource = "UI";
        if (oldMode != next.mode()) {
            LOG.info("Wallbox mode changed: {} -> {} (source=UI)", oldMode, next.mode());
        }
        boolean wasEnabled = preferences.enabled();
        preferences = next;
        if (!next.enabled()) activeMode = null;
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
            pauseCommandActive = true;
            consecutivePauseWithCurrent = 0;
            if (pending != null) LOG.info("Wallbox confirmed HR 2 pause command. Resuming with fresh measurements.");
            return retainedSample(battery, 0f, true, reason);
        } catch (IOException failure) {
            charger.close();
            String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            if (reason != null && !reason.equals(error)) error = reason + " / " + error;
            unconfirmedPause = new UnconfirmedPause(System.nanoTime() + RECOVERY_SILENCE, error);
            LOG.warn("Wallbox pause command unconfirmed [{}]. Retrying within the charger watchdog window: {}",
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
            policy = new ChargePolicy(settings.withPreferences(active), inverterMaxWatts);
            applied = active;
            activeMode = active.mode();
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
            logReadback(ev);
            long now = System.nanoTime();
            if (!ev.sessionActive()) {
                sessionStart = null;
            } else if (sessionStart == null) {
                sessionStart = now;
            }
            boolean detecting = sessionStart != null && now - sessionStart < TimeUnit.SECONDS.toNanos(45);
            float target = policy.target(ev, battery, now, kostal == null ? null : kostal.usablePvPowerW());
            logTarget(active.mode(), target);
            LOG.debug("Charger {} lifetime={} kWh on={} EV detection={} Sonnen={}",
                    ev, ev.lifetimeMwh() / 1e6, ev.enabled(), detecting, battery);
            Instant ir53Now = Instant.now();
            if (lastIr53Info == null || !ir53Now.isBefore(lastIr53Info.plusSeconds(600))) {
                LOG.info("Wallbox diagnostic IR 53={} ({})", ev.selectedMode(), ev.modeDescription());
                lastIr53Info = ir53Now;
            }
            if (!loggedMeterCheck && ev.powerKw() > 0 && battery != null && battery.hasPowerReadings()
                    && battery.gridFeedInW() != null) {
                double balanceW = battery.productionW() + battery.batteryW() - battery.gridFeedInW();
                LOG.info("Check meter wiring during stable charging: Consumption_W={} W, site balance={} W, "
                                + "EV={} kW, consumption-includes-wallbox={}.",
                        battery.consumptionW(), balanceW, ev.powerKw(), settings.consumptionIncludesWallbox());
                loggedMeterCheck = true;
            }
            LOG.debug("Wallbox rule cycle: {} installedMax={}A evMax={}A applied={}A",
                    policy.diagnostics(), ev.installedMaxAmps(), ev.evMaxAmps(), ev.appliedAmps());
            if (stopping || active != preferences) return;
            var write = writeTarget(target, active, started);
            if (write.hr8Amps() != null) lastWrittenTargetAmps = write.hr8Amps();
            lastAvailableWatts = policy.availableWatts();
            result = new Sample(ev, battery, write.targetAmps(), lastAvailableWatts, true, write.warning());
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

    private void logTarget(WallboxSettings.Mode mode, float target) {
        Instant now = Instant.now();
        if (lastLoggedTargetAmps == null || lastLoggedMode != mode || Math.abs(lastLoggedTargetAmps - target) > 0.001
                || lastTargetLog == null || !now.isBefore(lastTargetLog.plusSeconds(60))) {
            LOG.info("Wallbox setpoint: mode={} rawTargetA={} limits=[{}] finalA={} clampReason={}",
                    mode, String.format(java.util.Locale.ROOT, "%.3f", policy.rawTargetAmps()),
                    policy.diagnostics(), String.format(java.util.Locale.ROOT, "%.3f", target), policy.clampReason());
            lastLoggedTargetAmps = target;
            lastLoggedMode = mode;
            lastTargetLog = now;
        }
    }

    private void logReadback(ChargerReadings readings) {
        if (lastWrittenTargetAmps == null) return;
        Instant now = Instant.now();
        if (lastReadbackLog == null || !now.isBefore(lastReadbackLog.plusSeconds(60))) {
            LOG.info("Wallbox readback: lastWrittenHR8={}A IR4Applied={}A IR1028BoxLimit={}A IR2EvMax={}A IR1Phases={} IR1023HardwarePhases={} status={}",
                    lastWrittenTargetAmps, readings.appliedAmps(), readings.installedMaxAmps(),
                    readings.evMaxAmps(), readings.phases(), readings.hardwarePhases(), readings.status());
            lastReadbackLog = now;
        }
        boolean currentFlowing = readings.measuredActivePhases() > 0;
        if (pauseCommandActive) {
            consecutivePauseWithCurrent = currentFlowing ? consecutivePauseWithCurrent + 1 : 0;
            if (consecutivePauseWithCurrent >= 2
                    && (lastDeviationLog == null || !now.isBefore(lastDeviationLog.plusSeconds(60)))) {
                LOG.warn("Box übernimmt Pause nicht: HR 2 wurde bestätigt, aber Phasenstrom fließt; "
                                + "IR4={}A rawWords=[{}] L1/L2/L3={}A/{}A/{}A IR1={} IR53={} status={}",
                        readings.appliedAmps(), readings.appliedAmpsRawHex(), readings.l1().amps(),
                        readings.l2().amps(), readings.l3().amps(), readings.phases(),
                        readings.selectedMode(), readings.status());
                lastDeviationLog = now;
            }
            consecutiveTargetDeviations = 0;
            return;
        }
        boolean differs = Float.isFinite(readings.appliedAmps())
                && Math.abs(lastWrittenTargetAmps - readings.appliedAmps()) > 0.15f;
        consecutiveTargetDeviations = differs ? consecutiveTargetDeviations + 1 : 0;
        if (consecutiveTargetDeviations >= 2
                && (lastDeviationLog == null || !now.isBefore(lastDeviationLog.plusSeconds(60)))) {
            LOG.warn("Box übernimmt Sollwert nicht oder begrenzt ihn: HR8={}A IR4={}A rawWords=[{}] "
                            + "L1/L2/L3={}A/{}A/{}A IR1={} IR53={} IR1023={} IR1028={}A IR2={}A",
                    lastWrittenTargetAmps, readings.appliedAmps(), readings.appliedAmpsRawHex(),
                    readings.l1().amps(), readings.l2().amps(),
                    readings.l3().amps(), readings.phases(), readings.selectedMode(), readings.hardwarePhases(),
                    readings.installedMaxAmps(), readings.evMaxAmps());
            lastDeviationLog = now;
        }
    }

    private record Write(float targetAmps, Float hr8Amps, String warning) {}

    private void checkPositiveWrite(float amps, WallboxPreferences active, long started) throws IOException {
        if (amps > 0 && (stopping || active != preferences || System.nanoTime() - started >= MAX_SAMPLE_AGE)) {
            throw new IOException("Superseded or expired wallbox measurement");
        }
    }

    private Write writeTarget(float target, WallboxPreferences active, long started) throws IOException {
        if (target == 0 && pauseStrategy == PauseStrategy.PAUSE_COMMAND) {
            charger.confirmPause();
            pauseCommandActive = true;
            consecutivePauseWithCurrent = 0;
            return new Write(0, null, charger.recovery());
        }
        if (target == 0 && pauseStrategy == PauseStrategy.MIN_CURRENT
                && "below-minimum-current".equals(policy.clampReason()) && settings.gridLimitWatts() == 0) {
            float minimum = settings.minCurrentA();
            checkPositiveWrite(minimum, active, started);
            charger.setCurrent(minimum);
            pauseCommandActive = false;
            LOG.info("Pause strategy MIN_CURRENT keeps HR 8 at {} A", minimum);
            return new Write(minimum, minimum, charger.recovery());
        }
        if (target == 0) {
            charger.confirmPause();
            pauseCommandActive = true;
            consecutivePauseWithCurrent = 0;
            return new Write(0, null, charger.recovery());
        }
        checkPositiveWrite(target, active, started);
        try {
            charger.setCurrent(target);
            pauseCommandActive = false;
            return new Write(target, target, charger.recovery());
        } catch (InchModbusClient.DeviceException ex) {
            if (ex.code() != 12 || target == 0) {
                throw ex;
            }
            ChargerReadings fresh = charger.read();
            float max = fresh.maxAmps(settings.minCurrentA(), settings.maxCurrentA());
            float retry = fresh.sessionActive() && max < target ? max : 0;
            if (retry == 0) {
                policy.pause();
            }
            LOG.warn("Current setpoint too high: Modbus exception 12 for HR 8={} A. IR 1028 box ceiling={} A, "
                            + "IR 2 EV max={} A, effective ceiling={} A, retry={} A.",
                    target, fresh.installedMaxAmps(), fresh.evMaxAmps(), max, retry);
            if (retry == 0) {
                charger.confirmPause();
                pauseCommandActive = true;
                consecutivePauseWithCurrent = 0;
                return new Write(0, null, ex.getMessage());
            }
            checkPositiveWrite(retry, active, started);
            charger.setCurrent(retry);
            pauseCommandActive = false;
            return new Write(retry, retry, ex.getMessage());
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
