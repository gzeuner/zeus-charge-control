"use strict";

document.addEventListener("DOMContentLoaded", () => {
  const panel = document.getElementById("wallboxPanel");
  if (!panel) return;
  const labels = panel.dataset;
  const byId = (id) => document.getElementById(id);
  const toggle = byId("wbEnabled");
  const modes = [...panel.querySelectorAll("[data-mode]")];
  const smart = byId("wbSmart");
  const min = byId("wbMin");
  const reserve = byId("wbReserve");
  const states = labels.states.split("|");
  const phases = labels.phases.split("|");
  const modeDescriptions = labels.modeDescriptions.split("|");
  let settings,
    polling,
    interval,
    timer,
    editing = false,
    saving = false;

  function message(id, value) {
    const el = byId(id);
    el.hidden = !value;
    el.textContent = value || "";
  }

  function quantity(value, unit, digits = 0) {
    return number(value) === null ? "–" : format(value, digits) + " " + unit;
  }

  function measuredPhaseUse(ev) {
    const active = [ev?.l1, ev?.l2, ev?.l3]
      .map((phase, index) => ({ phase, line: `L${index + 1}` }))
      .filter(
        ({ phase }) => number(phase?.amps) !== null && number(phase.amps) > 1,
      );
    if (!active.length) {
      return "–";
    }
    return `${active.length} (${active.map(({ line }) => line).join(", ")}; ${labels.phaseMeasured})`;
  }

  function controls(ready) {
    for (const control of [toggle, ...modes, ...smart.elements]) {
      control.disabled = !ready || saving;
    }
  }

  function render(data) {
    settings = data.settings;
    const sample = data.sample;
    const ev = sample?.charger;
    const battery = sample?.battery;
    toggle.setAttribute("aria-checked", String(settings.enabled));
    text(
      "wbControlState",
      data.featureEnabled === false
        ? labels.disabled
        : settings.enabled
          ? labels.active
          : labels.off,
    );
    for (const btn of modes) {
      const active = btn.dataset.mode === settings.mode;
      btn.setAttribute("aria-pressed", String(active));
      if (active) text("wbModeHelp", btn.title);
    }
    smart.hidden = settings.mode !== "SMART";
    if (!editing) {
      min.value = settings.smartMinUsoc;
      reserve.value = settings.smartReservePercent;
    }
    text(
      "wbActiveMode",
      data.activeMode
        ? `${data.activeMode} (${data.modeSource || "config"})`
        : labels.waiting,
    );
    byId("wbLive").hidden = !settings.enabled;
    const state = byId("wbState");
    const code = ev?.status;
    const pausedByApp = data.lastCommand?.command === "PAUSE_HR2" && sample?.targetAmps === 0;
    const error = !!(sample?.error || data.modbusState === "ERROR" || code === 8);
    const stateInfo = error
      ? { text: labels.error, kind: "fault", icon: "fa-triangle-exclamation" }
      : code === 5
        ? { text: labels.vehiclePaused, kind: "vehicle", icon: "fa-car" }
        : pausedByApp || code === 6
          ? { text: labels.pauseApp, kind: "paused", icon: "fa-pause" }
          : code === 4
        ? { text: labels.charging, kind: "charging", icon: "fa-bolt" }
          : code === 3
            ? { text: states[3] || labels.waiting, kind: "waiting", icon: "fa-plug" }
            : { text: states[code] || labels.waiting, kind: "idle", icon: "fa-hourglass-half" };
    text("wbStateText", stateInfo.text);
    state.dataset.state = stateInfo.kind;
    byId("wbStateIcon").className = "fas " + stateInfo.icon;
    text("wbModeChip", data.activeMode || settings.mode);
    text(
      "wbLink",
      data.featureEnabled === false
        ? labels.disabled
        : !settings.enabled
          ? labels.offline
          : data.modbusState === "CONNECTED"
            ? labels.online
            : data.modbusState === "ERROR"
              ? labels.modbusError
              : data.modbusState === "RECONNECTING"
                ? labels.modbusReconnecting
                : labels.waiting,
    );
    byId("wbAccessLine").hidden = !data.lastSuccessfulRead;
    if (data.lastSuccessfulRead) {
      const date = new Date(data.lastSuccessfulRead);
      if (Number.isFinite(date.getTime())) {
        text("wbAccess", date.toLocaleString(locale));
        byId("wbAccess").dateTime = date.toISOString();
      }
    }
    byId("wbFallback").hidden = !data.watchdogPossible;
    text("wbFallback", labels.fallback.replace("{seconds}", data.boxWatchdogSeconds));
    const warnings = [sample?.error || data.modbusError];
    if (data.pauseUnconfirmed) warnings.unshift(labels.pauseUnconfirmed);
    if (data.boxCommandMismatch) warnings.unshift(labels.boxMismatch);
    if (settings.enabled && ev && !battery) warnings.push(labels.solarOffline);
    message("wbWarning", warnings.filter(Boolean).join(" · "));

    const command = data.lastCommand;
    let lastCommandText = labels.unavailable;
    if (command) {
      const at = command.at ? new Date(command.at) : null;
      const time = at && Number.isFinite(at.getTime())
        ? new Intl.DateTimeFormat("de-DE", { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(at)
        : "–";
      lastCommandText = command.command === "PAUSE_HR2"
        ? labels.pauseCommand + " · " + time
        : quantity(command.amps, "A", 1) + " (HR 8) · " + time;
    }
    const values = {
      power: quantity(ev?.powerKw, "kW", 2),
      target: lastCommandText,
      reported: data.boxReportedAppliedAmps == null ? labels.unavailable : quantity(data.boxReportedAppliedAmps, "A", 1),
      phaseUse: "–",
      hardwarePhases: quantity(ev?.hardwarePhases, "phases"),
      evMax: quantity(ev?.evMaxAmps, "A", 1),
      boxMax: quantity(ev?.installedMaxAmps, "A", 1),
      available:
        settings.mode === "POWER"
          ? labels.notRelevant
          : quantity(sample?.availableWatts, "W"),
      pv: quantity(battery?.Production_W, "W"),
      pvSource: sample?.pvSource || labels.unavailable,
      house: quantity(battery?.Consumption_W, "W"),
      grid: quantity(battery?.GridFeedIn_W, "W"),
      soc: quantity(battery?.USOC, "%"),
      batteryPower: quantity(battery?.Pac_total_W, "W"),
      lastWriteResponse: data.lastWriteResponse || labels.unavailable,
      modbusStatus: data.modbusState || labels.waiting,
      lastRead: data.lastSuccessfulRead ? new Date(data.lastSuccessfulRead).toLocaleString("de-DE") : labels.unavailable,
      ir4Raw: data.appliedAmpsRawHex || labels.unavailable,
      evMaxRaw: ev ? data.ir2RawHex + " (" + ev.evMaxAmps + " A)" : labels.unavailable,
      boxMaxRaw: ev ? data.ir1028RawHex + " (" + ev.installedMaxAmps + " A)" : labels.unavailable,
      hardwarePhasesRaw: ev ? ev.hardwarePhases + " / 0x" + data.ir1023RawHex : labels.unavailable,
      rawMode: ev
        ? ev.selectedMode + " / 0x" + data.ir53RawHex + " (" + (modeDescriptions[ev.selectedMode] || modeDescriptions[0]) + ")"
        : "–",
    };
    if (ev) {
      values.phaseUse = measuredPhaseUse(ev);
      values.phaseRaw = ev.phases + " / 0x" + data.ir1RawHex + " (" + (phases[ev.phases] || "Unbekannt") + ")";
      for (const key of ["l1", "l2", "l3"]) {
        const amps = number(ev[key]?.amps);
        const meter = panel.querySelector(`[data-meter="${key}"]`);
        const current = amps === null ? null : Math.max(0, Math.min(16, amps));
        meter.querySelector("[data-phase-fill]").style.width = (current === null ? 0 : current / 16 * 100) + "%";
        meter.querySelector("[data-phase-value]").textContent = current === null ? "–" : quantity(current, "A", 1);
        const cap = number(ev.installedMaxAmps);
        meter.querySelector("[data-phase-cap]").style.left = Math.max(0, Math.min(100, (cap === null ? 16 : cap) / 16 * 100)) + "%";
      }
      const grid = number(battery?.GridFeedIn_W);
      panel.querySelector('[data-metric="grid"]').dataset.flow = grid === null ? "unknown" : grid >= 0 ? "export" : "import";
      const soc = number(battery?.USOC);
      panel.querySelector("[data-soc-ring]").style.setProperty("--soc-angle", Math.max(0, Math.min(100, soc || 0)) * 3.6 + "deg");
      const batteryIcon = panel.querySelector("[data-battery-arrow]");
      batteryIcon.className = "fas " + (number(battery?.Pac_total_W) < 0 ? "fa-arrow-down" : "fa-arrow-up");
    } else {
      values.phaseRaw = "–";
      for (const meter of panel.querySelectorAll("[data-meter]")) {
        meter.querySelector("[data-phase-fill]").style.width = "0";
        meter.querySelector("[data-phase-value]").textContent = "–";
      }
      panel.querySelector('[data-metric="grid"]').dataset.flow = "unknown";
    }
    for (const el of panel.querySelectorAll("[data-metric]")) {
      el.textContent = values[el.dataset.metric];
    }
    controls(data.featureEnabled !== false);
    schedule(data.pollIntervalMs);
  }

  function schedule(ms) {
    if (interval === ms) return;
    interval = ms;
    clearInterval(timer);
    timer = setInterval(refresh, ms);
  }

  function renderStatus(data) {
    try {
      render(data);
      return true;
    } catch (error) {
      console.error("Wallbox status rendering failed", error);
      controls(false);
      message("wbWarning", labels.renderError);
      return false;
    }
  }

  async function refresh() {
    if (document.hidden || saving || polling) return;
    const request = new AbortController();
    polling = request;
    const timeout = setTimeout(() => request.abort(), 8000);
    try {
      const data = await jsonRequest(labels.statusUrl, {
        signal: request.signal,
      });
      if (polling === request) renderStatus(data);
    } catch {
      if (polling === request) {
        controls(false);
        byId("wbLive").hidden = true;
        byId("wbFallback").hidden = true;
        text("wbControlState", labels.offline);
        message("wbWarning", labels.apiError);
      }
    } finally {
      clearTimeout(timeout);
      if (polling === request) polling = null;
    }
  }

  async function save(changes) {
    if (saving) return;
    saving = true;
    polling?.abort();
    polling = null;
    controls(false);
    try {
      const data = await jsonRequest(labels.settingsUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(changes),
      });
      if (changes.smartMinUsoc !== undefined || changes.mode !== undefined)
        editing = false;
      if (renderStatus(data)) message("wbFeedback", t.actionSuccess);
    } catch {
      message("wbFeedback", labels.saveError);
    } finally {
      saving = false;
      controls(!!settings);
      setTimeout(refresh, 250);
    }
  }

  toggle.addEventListener("click", () => save({ enabled: !settings.enabled }));
  for (const btn of modes) {
    btn.addEventListener("click", () => save({ mode: btn.dataset.mode }));
  }
  smart.addEventListener("input", () => {
    editing = true;
  });
  smart.addEventListener("submit", (e) => {
    e.preventDefault();
    if (smart.reportValidity())
      save({
        smartMinUsoc: min.valueAsNumber,
        smartReservePercent: reserve.valueAsNumber,
      });
  });
  document.addEventListener("visibilitychange", refresh);
  schedule(8000);
  refresh();
});
