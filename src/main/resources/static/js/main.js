"use strict";

const app = window.AppData || {};
const t = app.translations || {};
const locale = document.documentElement.lang === "de" ? "de-DE" : "en-GB";
const themes = ["default", "dark", "energy", "solar", "ember"];
const tabs = [
  "market-prices",
  "scheduled-charging-periods",
  "cheapest-periods",
];
const views = [
  {
    key: "market-prices",
    prefix: "marketPrices",
    toggle: "toggleMarketPrices",
    type: "line",
  },
  {
    key: "scheduled-charging-periods",
    prefix: "chargingSchedule",
    toggle: "toggleChargingSchedule",
    type: "bar",
  },
  {
    key: "cheapest-periods",
    prefix: "cheapestPeriods",
    toggle: "toggleCheapestPeriods",
    type: "bar",
  },
];
const charts = new Map();
let datasets = {
  "market-prices": Array.isArray(app.marketPrices) ? app.marketPrices : [],
  "scheduled-charging-periods": Array.isArray(app.scheduledChargingPeriods)
    ? app.scheduledChargingPeriods
    : [],
  "cheapest-periods": Array.isArray(app.cheapestPeriods)
    ? app.cheapestPeriods
    : [],
};
let statusData = null;
let actionBusy = false;
let connected = false;
let dirtyHours = false;
let activeTab = "market-prices";
let statusRequest = null;
let requestGeneration = 0;
let priceMode = readStorage("priceMode") === "BRUTTO" ? "BRUTTO" : "NETTO";
let timeZone;

function readStorage(key) {
  try {
    return localStorage.getItem(key);
  } catch (_) {
    return null;
  }
}
function writeStorage(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch (_) {
    /* Preferences are optional. */
  }
}
function readUiState() {
  try {
    const value = JSON.parse(readStorage("chargeControlUiState") || "{}");
    return value && typeof value === "object" && !Array.isArray(value)
      ? value
      : {};
  } catch (_) {
    return {};
  }
}
function saveUiState(patch) {
  writeStorage(
    "chargeControlUiState",
    JSON.stringify({ ...readUiState(), ...patch }),
  );
}
function number(value) {
  if (
    value == null ||
    typeof value === "boolean" ||
    (typeof value === "string" && value.trim() === "")
  )
    return null;
  const result = Number(value);
  return Number.isFinite(result) ? result : null;
}
function format(value, decimals = 2) {
  const parsed = number(value);
  return parsed == null
    ? "—"
    : new Intl.NumberFormat(locale, {
        minimumFractionDigits: decimals,
        maximumFractionDigits: decimals,
      }).format(parsed);
}
function text(id, value) {
  const el = document.getElementById(id);
  if (el) el.textContent = value;
}
function css(name) {
  return getComputedStyle(document.documentElement)
    .getPropertyValue(name)
    .trim();
}
function dateTime(value, withDate = true) {
  const timestamp = number(value);
  if (timestamp == null) return "—";
  const date = new Date(timestamp);
  return Number.isNaN(date.getTime())
    ? "—"
    : new Intl.DateTimeFormat(locale, {
        ...(withDate ? { day: "2-digit", month: "2-digit" } : {}),
        hour: "2-digit",
        minute: "2-digit",
        ...(timeZone ? { timeZone } : {}),
      }).format(date);
}
function displayedPrice(item) {
  if (!item) return null;
  // A missing total must stay unavailable; a net price must never be presented as a total.
  return priceMode === "BRUTTO"
    ? number(item.displayPriceBruttoCt)
    : number(
        item.displayPriceNettoCt ??
          item.marketPrice ??
          item.price ??
          item.currentPrice,
      );
}
function basis() {
  return priceMode === "BRUTTO" ? t.totalBasis : t.marketBasis;
}
function notice(message, error = false) {
  const el = document.getElementById("actionNotice");
  el.hidden = false;
  el.classList.toggle("notice-error", error);
  el.setAttribute("role", error ? "alert" : "status");
  el.textContent = message;
}
function setControls() {
  const unavailable = !connected || !statusData?.batteryAvailable;
  document.querySelectorAll("[data-ui-action]").forEach((el) => {
    el.disabled = actionBusy || unavailable;
  });
  document.querySelectorAll("#nightWindowForm input").forEach((el) => {
    el.disabled = actionBusy || unavailable;
  });
  document.body.classList.toggle("is-busy", actionBusy);
  document
    .getElementById("controlPanelTitle")
    .closest("section")
    .setAttribute("aria-busy", String(actionBusy));
}
function connection(ok) {
  connected = ok;
  document.getElementById("connectionNotice").hidden = ok;
  document.getElementById("connectionState").classList.toggle("stale", !ok);
  document.body.classList.toggle("is-stale", !ok);
  text(
    "connectionLabel",
    ok
      ? statusData?.batteryAvailable
        ? t.connected
        : t.batteryUnavailable
      : t.stale,
  );
  setControls();
}
function selectAction(id, selected) {
  const button = document.getElementById(id);
  button.classList.toggle("selected", selected);
  button.setAttribute("aria-pressed", String(selected));
}
function renderStatus() {
  if (!statusData) return;
  const s = statusData;
  const available = s.batteryAvailable === true;
  const paused = s.currentMode === "idle";
  const forced = s.isCharging === true;
  selectAction("automaticBtn", !paused && !forced && available);
  selectAction("modeBtn", paused && available);
  selectAction("chargingBtn", forced && available);
  text(
    "modeStatus",
    available
      ? forced
        ? t.charging
        : paused
          ? t.idle
          : t.automatic
      : t.unknown,
  );
  text(
    "activityValue",
    !available
      ? t.unknown
      : s.batteryCharging
        ? t.charging
        : s.batteryDischarging
          ? t.discharging
          : t.standby,
  );
  text("capacityPercentValue", available ? format(s.stateOfCharge, 0) : "—");
  text(
    "capacityKwhValue",
    available && number(s.remainingCapacityWh) != null
      ? format(s.remainingCapacityWh / 1000, 1)
      : "—",
  );
  text(
    "totalCapacityKwh",
    number(s.totalCapacityWh) != null
      ? format(s.totalCapacityWh / 1000, 1)
      : "—",
  );
  text("productionValue", available ? format(s.productionW, 0) : "—");
  text("consumptionValue", available ? format(s.consumptionW, 0) : "—");
  text("batteryPowerValue", available ? format(s.batteryPowerW, 0) : "—");
  text("setpointValue", available ? format(s.lastSetpointW, 0) : "—");
  text("targetValue", format(s.targetStateOfCharge, 0));
  text("seasonLabel", s.activeSeasonLabel || "—");
  const soc = available ? number(s.stateOfCharge) : null;
  const ring = document.getElementById("chargeRing");
  ring.style.setProperty(
    "--charge",
    soc == null ? 0 : Math.max(0, Math.min(100, soc)),
  );
  if (soc == null) {
    ring.removeAttribute("aria-valuenow");
    ring.setAttribute("aria-valuetext", t.unknown);
  } else {
    ring.setAttribute("aria-valuenow", String(Math.max(0, Math.min(100, soc))));
    ring.removeAttribute("aria-valuetext");
  }
  document.getElementById("targetProgress").style.width =
    `${Math.max(0, Math.min(100, number(s.targetStateOfCharge) ?? 0))}%`;
  text("dropRateValue", available ? format(s.dropRate) : "—");
  text(
    "estimatedTimeToTargetValue",
    available ? format(s.estimatedTimeToTarget) : "—",
  );
  document.querySelector("#modeBtn [data-role=label]").textContent = paused
    ? t.resume
    : t.pause;
  document.querySelector("#chargingBtn [data-role=label]").textContent = forced
    ? t.stopCharging
    : t.startCharging;
  document
    .getElementById("nightChargingBtn")
    .setAttribute("aria-checked", String(s.nightChargingIdle === true));
  text(
    "nightState",
    s.nightIdleActive
      ? t.nightActive
      : s.nightChargingIdle
        ? t.nightEnabled
        : t.nightDisabled,
  );
  if (!dirtyHours) {
    document.getElementById("nightStartInput").value = s.nightStartHour;
    document.getElementById("nightEndInput").value = s.nightEndHour;
  }
  document.getElementById("batteryWarning").hidden = available;
  text(
    "batteryWarningText",
    s.batteryNotConfigured ? t.configured : t.batteryUnavailable,
  );
  renderPriceSummary();
  setControls();
}
function renderPriceSummary() {
  document.querySelectorAll(".price-basis").forEach((el) => {
    el.textContent = basis();
  });
  text("currentPriceValue", format(displayedPrice(statusData)));
  text("confirmationPrice", format(displayedPrice(statusData)));
  const upcoming = datasets["market-prices"].filter(
    (item) =>
      number(item.endTimestamp) > Date.now() &&
      number(item.marketPrice) != null,
  );
  const cheapest = upcoming.reduce(
    (best, item) =>
      !best || item.marketPrice < best.marketPrice ? item : best,
    null,
  );
  text("cheapestPriceValue", format(displayedPrice(cheapest)));
  text(
    "cheapestWindow",
    cheapest
      ? `${dateTime(cheapest.startTimestamp)} – ${dateTime(cheapest.endTimestamp)}`
      : t.noOpportunity,
  );
}
function buildChartOptions() {
  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    interaction: { mode: "index", intersect: false },
    plugins: {
      legend: { display: false },
      tooltip: {
        backgroundColor: css("--surface"),
        titleColor: css("--text"),
        bodyColor: css("--text"),
        borderColor: css("--border"),
        borderWidth: 1,
        padding: 12,
        callbacks: {
          label: (context) =>
            `${format(context.parsed.y)} ${t.unit} · ${basis()}`,
        },
      },
    },
    scales: {
      x: {
        type: "time",
        adapters: { date: { locale, ...(timeZone ? { zone: timeZone } : {}) } },
        time: {
          unit: "hour",
          displayFormats: { hour: "HH:mm" },
          tooltipFormat: "dd.MM. HH:mm",
        },
        grid: { display: false },
        border: { display: false },
        ticks: {
          color: css("--chart-text"),
          maxTicksLimit: 9,
          font: { size: 10 },
          maxRotation: 0,
        },
      },
      y: {
        beginAtZero: true,
        border: { display: false },
        grid: { color: css("--chart-grid"), drawTicks: false },
        ticks: {
          color: css("--chart-text"),
          maxTicksLimit: 6,
          padding: 12,
          font: { size: 10 },
        },
      },
    },
  };
}
function drawChart(view) {
  if (view.key !== activeTab) return;
  const container = document.getElementById(`${view.prefix}ChartContainer`);
  if (container.hidden) return;
  const data = datasets[view.key].map((item) => ({
    x: item.startTimestamp,
    y: displayedPrice(item),
  }));
  let chart = charts.get(view.key);
  const dataset = {
    data,
    label: basis(),
    borderColor: css("--chart-line"),
    backgroundColor: css("--chart-fill"),
    borderWidth: 2,
    pointRadius: 0,
    pointHitRadius: 15,
    pointHoverRadius: 4,
    tension: 0,
    stepped: view.type === "line" ? "after" : false,
    fill: view.type === "line",
    borderRadius: view.type === "bar" ? 5 : 0,
    maxBarThickness: 45,
  };
  if (!chart) {
    chart = new Chart(document.getElementById(`${view.prefix}Chart`), {
      type: view.type,
      data: { datasets: [dataset] },
      options: buildChartOptions(),
    });
    charts.set(view.key, chart);
  } else {
    chart.data.datasets[0] = dataset;
    chart.options = buildChartOptions();
    chart.update("none");
    chart.resize();
  }
}
function viewMode(key) {
  return readUiState().viewModes?.[key] === "table" ? "table" : "chart";
}
function renderData(view) {
  const data = datasets[view.key];
  const empty = data.length === 0;
  const tableMode = viewMode(view.key) === "table";
  const table = document.getElementById(`${view.prefix}Table`);
  const tbody = document.getElementById(`${view.prefix}Tbody`);
  const emptyEl = document.getElementById(`${view.prefix}Empty`);
  emptyEl.hidden = !empty;
  emptyEl.textContent =
    view.key === "scheduled-charging-periods" ? t.emptyPlan : t.empty;
  table.hidden = empty || !tableMode;
  document.getElementById(`${view.prefix}ChartContainer`).hidden =
    empty || tableMode;
  const button = document.getElementById(view.toggle);
  button.hidden = empty;
  button.setAttribute("aria-pressed", String(tableMode));
  button.querySelector("span").textContent = tableMode ? t.chart : t.table;
  button.querySelector("i").className =
    `fas ${tableMode ? "fa-chart-simple" : "fa-table"}`;
  tbody.replaceChildren(
    ...data.map((item) => {
      const row = document.createElement("tr");
      if (item.startTimestamp <= Date.now() && item.endTimestamp > Date.now())
        row.className = "highlight";
      for (const value of [
        dateTime(item.startTimestamp),
        dateTime(item.endTimestamp),
        format(displayedPrice(item)),
      ]) {
        const cell = document.createElement("td");
        cell.textContent = value;
        row.append(cell);
      }
      return row;
    }),
  );
  if (!empty && !tableMode) drawChart(view);
}
function renderAllData() {
  renderPriceSummary();
  views.forEach(renderData);
}
function applyTheme(theme) {
  const resolved = themes.includes(theme) ? theme : "default";
  document.documentElement.dataset.theme = resolved;
  document.getElementById("themeSelector").value = resolved;
  writeStorage("chargeControlTheme", resolved);
  renderAllData();
}
function validHours() {
  const inputs = ["nightStartInput", "nightEndInput"].map((id) =>
    document.getElementById(id),
  );
  const values = inputs.map((input) =>
    /^\d{1,2}$/.test(input.value) ? Number(input.value) : NaN,
  );
  const valid =
    values.every(
      (value) => Number.isInteger(value) && value >= 0 && value <= 23,
    ) && values[0] !== values[1];
  inputs.forEach((input) => input.setAttribute("aria-invalid", String(!valid)));
  if (!valid) {
    text("nightWindowStatus", t.invalidHours);
    document.getElementById("nightWindowStatus").classList.add("error");
    inputs
      .find(
        (_, i) =>
          !Number.isInteger(values[i]) || values[i] < 0 || values[i] > 23,
      )
      ?.focus();
    return null;
  }
  return { startHour: values[0], endHour: values[1] };
}
async function jsonRequest(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    signal: options.signal || AbortSignal.timeout(8000),
    cache: "no-store",
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}
async function fetchStatus(force = false) {
  if (document.hidden && !force) return;
  if (statusRequest) {
    if (!force) return;
    statusRequest.abort();
  }
  const controller = new AbortController();
  const generation = ++requestGeneration;
  statusRequest = controller;
  const timeout = setTimeout(() => controller.abort(), 8000);
  const refresh = document.getElementById("refreshStatus");
  refresh.disabled = true;
  try {
    const data = await jsonRequest("/current-status", {
      signal: controller.signal,
    });
    if (generation !== requestGeneration) return;
    if (
      !data ||
      typeof data.batteryAvailable !== "boolean" ||
      typeof data.currentMode !== "string"
    )
      throw new Error("Invalid status payload");
    statusData = data;
    timeZone = data.timeZone;
    for (const view of views) {
      const key =
        view.key === "market-prices"
          ? "marketPrices"
          : view.key === "cheapest-periods"
            ? "cheapestPeriods"
            : "scheduledChargingPeriods";
      if (Array.isArray(data[key])) datasets[view.key] = data[key];
    }
    connection(true);
    renderStatus();
    renderAllData();
    text("lastUpdated", `${t.lastUpdated} ${dateTime(Date.now(), false)}`);
  } catch (error) {
    if (generation === requestGeneration) connection(false);
  } finally {
    clearTimeout(timeout);
    if (generation === requestGeneration) {
      statusRequest = null;
      refresh.disabled = false;
    }
  }
}
async function executeAction(url, body, onSuccess) {
  if (actionBusy || !connected || !statusData?.batteryAvailable) return;
  actionBusy = true;
  // Retire any earlier status request so it cannot overwrite the acknowledged result.
  ++requestGeneration;
  statusRequest?.abort();
  statusRequest = null;
  setControls();
  try {
    const result = await jsonRequest(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (result.success !== true)
      throw new Error("Action rejected by battery service");
    if (onSuccess) onSuccess();
    notice(t.actionSuccess);
  } catch (error) {
    notice(t.actionFailed, true);
  } finally {
    actionBusy = false;
    await fetchStatus(true);
    setControls();
  }
}
document.addEventListener("DOMContentLoaded", () => {
  const stored = readUiState();
  activeTab = tabs.includes(stored.activeTab)
    ? stored.activeTab
    : "market-prices";
  document
    .getElementById("themeSelector")
    .addEventListener("change", (event) => applyTheme(event.target.value));
  document
    .getElementById("languageSelector")
    .addEventListener("change", (event) => {
      const url = new URL(window.location.href);
      url.searchParams.set("lang", event.target.value);
      window.location.assign(url);
    });
  const priceToggle = document.getElementById("priceModeToggle");
  priceToggle.checked = priceMode === "BRUTTO";
  priceToggle.addEventListener("change", () => {
    priceMode = priceToggle.checked ? "BRUTTO" : "NETTO";
    writeStorage("priceMode", priceMode);
    renderAllData();
  });
  document
    .getElementById("refreshStatus")
    .addEventListener("click", () => fetchStatus(true));
  document
    .getElementById("retryStatus")
    .addEventListener("click", () => fetchStatus(true));
  document
    .getElementById("automaticBtn")
    .addEventListener("click", () =>
      executeAction("/toggle-mode", { mode: "standard" }),
    );
  document.getElementById("modeBtn").addEventListener("click", () =>
    executeAction("/toggle-mode", {
      mode: statusData?.currentMode === "idle" ? "standard" : "idle",
    }),
  );
  const modalEl = document.getElementById("confirmationModal");
  const modal = bootstrap.Modal.getOrCreateInstance(modalEl);
  document.getElementById("chargingBtn").addEventListener("click", () => {
    if (statusData?.isCharging)
      executeAction("/toggle-charging", { charging: "stop" });
    else if (!actionBusy) {
      text("confirmationPrice", format(displayedPrice(statusData)));
      modal.show();
    }
  });
  modalEl.addEventListener("shown.bs.modal", () =>
    document.getElementById("cancelStart").focus(),
  );
  modalEl.addEventListener("hidden.bs.modal", () =>
    document.getElementById("chargingBtn").focus(),
  );
  document.getElementById("confirmStart").addEventListener("click", () => {
    modal.hide();
    executeAction("/toggle-charging", { charging: "start" });
  });
  ["nightStartInput", "nightEndInput"].forEach((id) => {
    document.getElementById(id).addEventListener("input", (event) => {
      dirtyHours = true;
      text("nightWindowStatus", t.unsaved);
      document.getElementById("nightWindowStatus").classList.remove("error");
      event.target.removeAttribute("aria-invalid");
    });
  });
  const savedHours = () => {
    dirtyHours = false;
    text("nightWindowStatus", t.saved);
    document.getElementById("nightWindowStatus").classList.remove("error");
  };
  document
    .getElementById("nightWindowForm")
    .addEventListener("submit", (event) => {
      event.preventDefault();
      const hours = validHours();
      if (hours) executeAction("/night-charging-window", hours, savedHours);
    });
  document.getElementById("nightChargingBtn").addEventListener("click", () => {
    const next = !statusData?.nightChargingIdle;
    const hours = next ? validHours() : {};
    if (hours)
      executeAction(
        "/toggle-night-charging",
        { nightChargingIdle: next, ...hours },
        next ? savedHours : null,
      );
  });
  views.forEach((view) => {
    bootstrap.Tab.getOrCreateInstance(
      document.getElementById(`${view.key}-tab`),
    );
    document.getElementById(view.toggle).addEventListener("click", () => {
      saveUiState({
        viewModes: {
          ...(readUiState().viewModes || {}),
          [view.key]: viewMode(view.key) === "chart" ? "table" : "chart",
        },
      });
      renderData(view);
    });
    document
      .getElementById(`${view.key}-tab`)
      .addEventListener("shown.bs.tab", () => {
        activeTab = view.key;
        saveUiState({ activeTab });
        renderData(view);
      });
  });
  applyTheme(readStorage("chargeControlTheme"));
  bootstrap.Tab.getOrCreateInstance(
    document.getElementById(`${activeTab}-tab`),
  ).show();
  setControls();
  fetchStatus(true);
  setInterval(() => {
    if (!actionBusy) fetchStatus();
  }, 10000);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && !actionBusy) fetchStatus(true);
  });
});
