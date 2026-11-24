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
      const reported = phases[ev?.phases];
      if (reported && reported !== "Unbekannt" && reported !== "Unknown")
        return reported;
      const hardware = number(ev?.hardwarePhases);
      if (hardware !== null && hardware >= 1 && hardware <= 3)
        return `${hardware} (IR 1023)`;
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
    state.textContent =
      states[ev?.status] ||
      (sample && !sample.connected ? labels.offline : labels.waiting);
    state.dataset.state =
      ev?.status === 4 ? "charging" : ev?.status === 8 ? "fault" : "idle";
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
    const warnings = [sample?.error || data.modbusError];
    if (data.pauseUnconfirmed) warnings.unshift(labels.pauseUnconfirmed);
    if (settings.enabled && ev && !battery) warnings.push(labels.solarOffline);
    message("wbWarning", warnings.filter(Boolean).join(" · "));

    const values = {
      power: quantity(ev?.powerKw, "kW", 2),
      current: ev
        ? [ev.l1, ev.l2, ev.l3]
            .map((p) => quantity(p?.amps, "A", 1))
            .join(" / ")
        : "–",
      target:
        sample?.targetAmps === 0
          ? data.lastWrittenTargetAmps == null
            ? labels.paused
            : `${quantity(data.lastWrittenTargetAmps, "A", 1)} · ${labels.paused}`
          : quantity(data.lastWrittenTargetAmps ?? sample?.targetAmps, "A", 1),
      reported: quantity(data.boxReportedAppliedAmps, "A", 1),
      phaseUse: phases[ev?.phases] || "–",
      hardwarePhases: quantity(ev?.hardwarePhases, "phases"),
      evMax: quantity(ev?.evMaxAmps, "A", 1),
      boxMax: quantity(ev?.installedMaxAmps, "A", 1),
      available:
        settings.mode === "POWER"
          ? labels.ignored
          : quantity(sample?.availableWatts, "W"),
      pv: quantity(battery?.Production_W, "W"),
      house: quantity(battery?.Consumption_W, "W"),
      grid: quantity(battery?.GridFeedIn_W, "W"),
      soc: quantity(battery?.USOC, "%"),
      batteryPower: quantity(battery?.Pac_total_W, "W"),
      rawMode: ev
        ? `${ev.selectedMode} (${modeDescriptions[ev.selectedMode] || modeDescriptions[0]})`
        : "–",
    };
    if (ev) {
      values.phaseUse = measuredPhaseUse(ev);
      values.phaseRaw = `${ev.phases} (${phases[ev.phases] || "Unbekannt"})`;
    } else {
      values.phaseRaw = "–";
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

  async function refresh() {
    if (document.hidden || saving || polling) return;
    const request = new AbortController();
    polling = request;
    const timeout = setTimeout(() => request.abort(), 8000);
    try {
      const data = await jsonRequest(labels.statusUrl, {
        signal: request.signal,
      });
      if (polling === request) render(data);
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
      render(data);
      message("wbFeedback", t.actionSuccess);
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
