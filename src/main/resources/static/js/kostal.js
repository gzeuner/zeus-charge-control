"use strict";

document.addEventListener("DOMContentLoaded", () => {
  const panel = document.getElementById("kostalPanel");
  if (!panel) return;
  const labels = panel.dataset;
  const text = (id, value) => {
    document.getElementById(id).textContent = value;
  };
  const watts = (value) =>
    Number.isFinite(value) ? `${Math.round(value)} W` : "—";
  const states = {
    deaktiviert: ["disabled", labels.disabled],
    verbunden: ["connected", labels.connected],
    stale: ["stale", labels.stale],
    getrennt: ["disconnected", labels.disconnected],
    "Fehlerhafte Konfiguration": ["error", labels.configError],
  };
  async function refresh() {
    try {
      const response = await fetch(panel.dataset.statusUrl, {
        cache: "no-store",
      });
      if (!response.ok) throw new Error("status unavailable");
      const data = await response.json();
      const item = data.inverter;
      const rawState = !data.enabled
        ? "deaktiviert"
        : item?.status || "getrennt";
      const [state, stateLabel] = states[rawState] || ["error", rawState];
      const stateElement = document.getElementById("kostalState");
      stateElement.textContent = stateLabel;
      stateElement.dataset.state = state;
      text("kostalAc", watts(item?.pvAcPowerW));
      text("kostalDc", watts(item?.pvDcPowerW));
      text("kostalSource", data.pvSource || "SONNEN");
      const last = document.getElementById("kostalLast");
      last.textContent = item?.lastValidAt
        ? new Date(item.lastValidAt).toLocaleString()
        : "—";
      if (item?.lastValidAt) last.dateTime = item.lastValidAt;
      const fallback = document.getElementById("kostalFallback");
      fallback.hidden =
        !data.enabled || data.pvSource !== "KOSTAL" || state === "connected";
      fallback.textContent = labels.fallback;
    } catch {
      text("kostalState", labels.unavailable);
    }
  }
  refresh();
  window.setInterval(refresh, 5000);
});
