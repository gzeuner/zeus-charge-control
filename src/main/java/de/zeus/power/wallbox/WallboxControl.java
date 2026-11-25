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
    // Space recovery retries to avoid rapid duplicate pause commands.
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
    private volatile WallboxSettings.Mode activeMode;
    private volatile String modeSource = "config";
    private volatile Float lastWrittenTargetAmps;
    private volatile Float lastAcceptedTargetAmps;
    private volatile LastCommand lastCommand;
    private Instant lastHr8WriteAt;
    private final int keepaliveMs;
    private final int boxWatchdogSeconds;
    private Instant lastReadbackLog;
    private volatile boolean pauseCommandActive;
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
    private final PhaseTracker phaseTracker = new PhaseTracker();
    private volatile int effectivePhases;
    private volatile String phaseSource = "wallbox.phases";
    private volatile WallboxSetpointStatus setpointStatus = WallboxSetpointStatus.KEINE_SESSION;
    private volatile String boxModel;
    private volatile String boxSoftwareVersion;
    private long lastIdentityGeneration;
    private long successfulPolls;
    private long lastAcceptedWritePoll = -1;
    private int acceptedWritesDuringFlow;
    private Instant flowStarted;
    private Instant lastIgnoredWarning;
    private int lastPolledStatus = -1;
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
                   @Value("${wallbox.keepalive-ms:10000}") int keepaliveMs,
                   @Value("${wallbox.box-watchdog-seconds:35}") int boxWatchdogSeconds) throws IOException {
        this.settings = settings;
        this.propertyEnabled = enabled;
        this.featureEnabled = enabled;
        if (keepaliveMs < 1 || boxWatchdogSeconds < 1) throw new IllegalArgumentException("Invalid wallbox keepalive/watchdog settings");
        this.boxWatchdogSeconds = boxWatchdogSeconds;
        this.keepaliveMs = (int) Math.min(keepaliveMs, Math.max(1L, (long) boxWatchdogSeconds * 800L));
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
        LOG.info("Effective wallbox settings: mode={} source={} minimum={}A maximum={}A gridLimit={}W phases={} voltage={}V keepalive={}ms watchdog={}s",
                preferences.mode(), modeSource, settings.minCurrentA(), settings.maxCurrentA(),
                settings.gridLimitWatts(), settings.phases(), settings.voltage(),
                this.keepaliveMs, this.boxWatchdogSeconds);

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
                  boolean connected, String error, String pvSource) {}

    record LastCommand(String command, Float amps, Instant at, String response) {}

    record Status(WallboxPreferences settings, WallboxSettings.Mode activeMode, String modeSource,
                  Float lastWrittenTargetAmps, Float boxReportedAppliedAmps, int pollIntervalMs, Sample sample, Instant lastAccess,
                  Instant lastSuccessfulRead, InchModbusClient.ConnectionState modbusState,
                  String modbusError, boolean watchdogPossible, boolean pauseUnconfirmed, boolean featureEnabled,
                  LastCommand lastCommand, String lastWriteResponse, String appliedAmpsRawHex,
                  String ir1RawHex, String ir2RawHex, String ir53RawHex, String ir1023RawHex,
                  String ir1028RawHex, int keepaliveMs, int boxWatchdogSeconds,
                  boolean boxCommandMismatch, Float commandedTargetAmps,
                  WallboxSetpointStatus setpointStatus, int effectivePhases, String phaseSource,
                  String boxModel, String boxSoftwareVersion) {}

    synchronized Status status() {
        Instant last = charger == null ? null : charger.lastAccess();
        Instant reference = last == null ? monitoringSince : last;
        boolean fallback = reference != null && !Instant.now().isBefore(reference.plusSeconds(boxWatchdogSeconds));
        ChargerReadings readings = sample != null && sample.charger() != null ? sample.charger() : lastReadings;
        Float appliedCurrent = readings == null || !Float.isFinite(readings.appliedAmps())
                ? null : readings.appliedAmps();
        return new Status(preferences, activeMode, modeSource, lastWrittenTargetAmps,
                appliedCurrent, Math.min(settings.pollIntervalMs(), keepaliveMs), sample, last,
                charger == null ? null : charger.lastSuccessfulRead(),
                charger == null ? null : charger.connectionState(), charger == null ? null : charger.lastError(),
                fallback, unconfirmedPause != null, featureEnabled, lastCommand,
                charger == null ? null : charger.lastWriteResponse(),
                readings == null ? null : readings.appliedAmpsRawHex(),
                readings == null ? null : rawWord(readings.phases()),
                readings == null ? null : readings.evMaxRawHex(),
                readings == null ? null : rawWord(readings.selectedMode()),
                readings == null ? null : rawWord(readings.hardwarePhases()),
                readings == null ? null : readings.installedMaxRawHex(),
                keepaliveMs, boxWatchdogSeconds,
                setpointStatus == WallboxSetpointStatus.BOX_IGNORIERT_SOLLWERT,
                lastAcceptedTargetAmps, setpointStatus, effectivePhases, phaseSource,
                boxModel, boxSoftwareVersion);
    }

    private static String rawWord(int value) {
        return String.format(java.util.Locale.ROOT, "%04X", value & 0xffff);
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
        periodic = scheduler.scheduleAtFixedRate(this::poll, 0,
                Math.min(settings.pollIntervalMs(), keepaliveMs), TimeUnit.MILLISECONDS);
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
            if (!preferences.enabled()) sample = new Sample(null, null, null, null, false, pause.error(), null);
        }
    }

    // The polling worker is the sole writer of recovery state, including across GUI mode changes.
    private Sample pauseOrWait(SonnenReadings battery, String reason) {
        var pending = unconfirmedPause;
        if (pending != null && System.nanoTime() - pending.retryAfter() < 0) {
            return retainedSample(battery, null, false, pending.error());
        }
        try {
            beforeBoxWrite();
            charger.confirmPause();
            rememberCommand("PAUSE_HR2", null);
            initialPauseRequired = false;
            unconfirmedPause = null;
            pauseCommandActive = true;
            if (pending != null) LOG.info("Wallbox pause confirmed; refreshing measurements.");
            return retainedSample(battery, 0f, true, reason);
        } catch (IOException failure) {
            charger.close();
            String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            if (reason != null && !reason.equals(error)) error = reason + " / " + error;
            unconfirmedPause = new UnconfirmedPause(System.nanoTime() + RECOVERY_SILENCE, error);
            LOG.warn("Wallbox pause not confirmed; retrying. {}", error);
            return retainedSample(battery, null, false, error);
        }
    }

    private Sample retainedSample(SonnenReadings battery, Float target, boolean connected, String error) {
        return new Sample(lastReadings, battery != null ? battery : lastBatteryReadings, target,
                lastAvailableWatts, connected, error, null);
    }

    private void beforeBoxWrite() {
        Instant now = Instant.now();
        Instant lastAccess = charger.lastAccess();
        long gapMillis = lastAccess == null ? 0 : Duration.between(lastAccess, now).toMillis();
        if (lastAccess != null && watchdogWriteGapExceeded(gapMillis, boxWatchdogSeconds)) {
            LOG.warn("Wallbox connection needs attention: no recent write for {} ms (interval={} s)",
                    gapMillis, boxWatchdogSeconds);
        }
    }

    static boolean watchdogWriteGapExceeded(long gapMillis, int watchdogSeconds) {
        return gapMillis > (long) watchdogSeconds * 800L;
    }

    static boolean shouldWriteTarget(Float lastHr8Amps, float nextWholeAmpTarget, boolean statusChanged,
                                     Instant lastHr8WriteAt, Instant now) {
        boolean changed = lastHr8Amps == null || Math.abs(lastHr8Amps - nextWholeAmpTarget) > 0.01f;
        boolean refreshDue = lastHr8WriteAt == null || !now.isBefore(lastHr8WriteAt.plusSeconds(60));
        return changed || statusChanged || refreshDue;
    }

    private void rememberCommand(String command, Float amps) {
        Instant now = Instant.now();
        lastCommand = new LastCommand(command, amps, now, charger.lastWriteResponse());
        lastAcceptedTargetAmps = "PAUSE_HR2".equals(command) ? 0f : amps;
        lastAcceptedWritePoll = successfulPolls;
        if (flowStarted != null) acceptedWritesDuringFlow++;
        if ("HR8".equals(command)) lastHr8WriteAt = now;
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
        // Resume control before continuing the regular polling cycle.
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
            successfulPolls++;
            lastReadings = ev;
            lastBatteryReadings = battery;
            long connectionGeneration = charger.connectionGeneration();
            if (connectionGeneration != lastIdentityGeneration) {
                lastIdentityGeneration = connectionGeneration;
                try {
                    InchModbusClient.Identity identity = charger.readIdentity();
                    boxModel = identity.model();
                    boxSoftwareVersion = identity.softwareVersion();
                    LOG.info("Wallbox self-test passed: model={} softwareVersion={}", boxModel, boxSoftwareVersion);
                } catch (IOException identityFailure) {
                    LOG.warn("Wallbox self-test failed after connection: {}", identityFailure.getMessage());
                }
            }
            Instant wallNow = Instant.now();
            PhaseTracker.Result phaseResult = phaseTracker.update(ev, settings.phases());
            effectivePhases = phaseResult.phases();
            phaseSource = phaseResult.source();
            updateFlow(ev, wallNow);
            boolean managedSession = managedSession(ev.status());
            boolean statusChanged = managedSession && ev.status() != lastPolledStatus;
            boolean sessionStarted = managedSession && !managedSession(lastPolledStatus);
            if (sessionStarted) pauseCommandActive = false;
            lastPolledStatus = ev.status();
            logReadback(ev);
            long now = System.nanoTime();
            if (!managedSession) {
                sessionStart = null;
            } else if (sessionStart == null) {
                sessionStart = now;
            }
            Double pvOverride = kostal == null ? null : kostal.usablePvPowerW();
            String pvSource = active.mode() == WallboxSettings.Mode.POWER ? null
                    : pvOverride != null ? "KOSTAL"
                    : battery != null && battery.productionW() != null ? "Sonnen" : null;
            float target = policy.target(ev, battery, now, pvOverride,
                    lastAcceptedTargetAmps, phaseResult.stableMeasuredPhases());
            logTarget(active.mode(), target);
            LOG.debug("Wallbox status: session={} applied={} A power={} kW",
                    ev.sessionActive(), ev.appliedAmps(), ev.powerKw());
            Instant ir53Now = Instant.now();
            if (lastIr53Info == null || !ir53Now.isBefore(lastIr53Info.plusSeconds(600))) {
                LOG.info("Wallbox diagnostics refreshed.");
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
            var write = writeTarget(target, active, started, statusChanged);
            if (write.hr8Amps() != null) lastWrittenTargetAmps = write.hr8Amps();
            lastAvailableWatts = policy.availableWatts();
            result = new Sample(ev, battery, write.targetAmps(), lastAvailableWatts, true, write.warning(), pvSource);
            setpointStatus = WallboxSetpointStatus.evaluate(ev.status(), lastAcceptedTargetAmps,
                    lastCommand != null, flowStarted, Instant.now(), acceptedWritesDuringFlow, ev);
            if (setpointStatus == WallboxSetpointStatus.BOX_IGNORIERT_SOLLWERT
                    && (lastIgnoredWarning == null || !Instant.now().isBefore(lastIgnoredWarning.plusSeconds(300)))) {
                LOG.warn("Wallbox übernimmt den Sollwert nicht. Bitte Ladestecker ab- und wieder anstecken.");
                lastIgnoredWarning = Instant.now();
            }
        } catch (IOException | RuntimeException ex) {
            policy.pause();
            LOG.warn("Wallbox Modbus cycle failed [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
            result = pauseOrWait(battery, ex.toString());
        }
        publish(active, result);
    }

    private static boolean managedSession(int status) { return status >= 4 && status <= 7; }

    private void updateFlow(ChargerReadings readings, Instant now) {
        float total = readings.l1().amps() + readings.l2().amps() + readings.l3().amps();
        if (managedSession(readings.status()) && Float.isFinite(total) && total > 1) {
            if (flowStarted == null) {
                flowStarted = now;
                acceptedWritesDuringFlow = 0;
            }
        } else {
            flowStarted = null;
            acceptedWritesDuringFlow = 0;
        }
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
            LOG.info("Wallbox target: mode={} current={} A", mode,
                    String.format(java.util.Locale.ROOT, "%.3f", target));
            lastLoggedTargetAmps = target;
            lastLoggedMode = mode;
            lastTargetLog = now;
        }
    }

    private void logReadback(ChargerReadings readings) {
        if (lastAcceptedTargetAmps == null) return;
        Instant now = Instant.now();
        boolean differs = readbackDiffers(readings, lastAcceptedTargetAmps,
                (int) Math.max(0, successfulPolls - lastAcceptedWritePoll));
        if (lastReadbackLog == null || !now.isBefore(lastReadbackLog.plusSeconds(60))) {
            LOG.info("Wallbox current: requested={} A applied={} A matches={}",
                    lastAcceptedTargetAmps, readings.appliedAmps(), !differs);
            lastReadbackLog = now;
        }
    }

    static boolean readbackDiffers(ChargerReadings readings, Float requestedAmps, int pollsSinceAcceptedWrite) {
        if (requestedAmps == null || pollsSinceAcceptedWrite < 2 || readings.status() < 4 || readings.status() > 7
                || !Float.isFinite(readings.appliedAmps())) return false;
        float expected = requestedAmps >= 6 ? (float) Math.floor(requestedAmps) : 0;
        return Math.abs(expected - readings.appliedAmps()) > 0.1f;
    }

    private record Write(float targetAmps, Float hr8Amps, String warning) {}

    private void checkPositiveWrite(float amps, WallboxPreferences active, long started) throws IOException {
        if (amps > 0 && (stopping || active != preferences || System.nanoTime() - started >= MAX_SAMPLE_AGE)) {
            throw new IOException("Superseded or expired wallbox measurement");
        }
    }

    private Write writeTarget(float target, WallboxPreferences active, long started,
                              boolean statusChanged) throws IOException {
        float wholeAmps = Float.isFinite(target) && target > 0 ? (float) Math.floor(target + 1e-9) : 0;
        if (wholeAmps < settings.minCurrentA()) {
            if (!pauseCommandActive) {
                beforeBoxWrite();
                charger.confirmPause();
                rememberCommand("PAUSE_HR2", null);
            }
            pauseCommandActive = true;
            return new Write(0, null, charger.recovery());
        }
        checkPositiveWrite(wholeAmps, active, started);
        Float lastHr8Amps = lastCommand != null && "HR8".equals(lastCommand.command())
                ? lastCommand.amps() : null;
        if (!shouldWriteTarget(lastHr8Amps, wholeAmps, statusChanged, lastHr8WriteAt, Instant.now()))
            return new Write(wholeAmps, null, charger.recovery());
        try {
            beforeBoxWrite();
            charger.setCurrent(wholeAmps);
            rememberCommand("HR8", wholeAmps);
            pauseCommandActive = false;
            return new Write(wholeAmps, wholeAmps, charger.recovery());
        } catch (InchModbusClient.DeviceException ex) {
            if (ex.code() != 12) throw ex;
            ChargerReadings fresh = charger.read();
            float max = fresh.maxAmps(settings.minCurrentA(), settings.maxCurrentA());
            float retry = fresh.sessionActive() && max < wholeAmps ? (float) Math.floor(max) : 0;
            if (retry < settings.minCurrentA()) retry = 0;
            if (retry == 0) policy.pause();
            LOG.warn("Wallbox rejected the target: requested={} A, retry={} A.", wholeAmps, retry);
            if (retry == 0) {
                charger.confirmPause();
                rememberCommand("PAUSE_HR2", null);
                pauseCommandActive = true;
                return new Write(0, null, ex.getMessage());
            }
            checkPositiveWrite(retry, active, started);
            beforeBoxWrite();
            charger.setCurrent(retry);
            rememberCommand("HR8", retry);
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
                LOG.warn("Wallbox shutdown timed out; stopping retries.");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ex) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
