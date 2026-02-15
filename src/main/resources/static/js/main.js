// Global variables

// ===== Luna: 'Last-click-wins' - UI state helpers =====
const LAST_ACTION_KEY = 'tt_last_action_ts';
const LAST_MODE_KEY   = 'tt_last_mode'; // 'idle' | 'standard' | 'forced' | 'nightIdle'

// ===== Price mode toggle =====
const PRICE_MODE_KEY = 'priceMode';
const PRICE_MODE_NETTO = 'NETTO';
const PRICE_MODE_BRUTTO = 'BRUTTO';
let priceMode = PRICE_MODE_NETTO;

function nowMs() { return Date.now(); }

function setLastAction(mode) {
  try {
    localStorage.setItem(LAST_ACTION_KEY, String(nowMs()));
    if (mode) localStorage.setItem(LAST_MODE_KEY, mode);
  } catch(e) { /* ignore */ }
}

function getLastActionAgeMs() {
  try {
    const ts = Number(localStorage.getItem(LAST_ACTION_KEY) || '0');
    return ts > 0 ? (nowMs() - ts) : Number.POSITIVE_INFINITY;
  } catch(e) { return Number.POSITIVE_INFINITY; }
}

// For a brief window after a user click, don't let polling flip the UI back.
const USER_ACTION_SUPPRESS_MS = 5000;

// Force UI exclusivity: when one mode is selected, reset others accordingly
function setExclusiveModeUI(mode) {
  // mode: 'idle' | 'standard' | 'forced' | 'nightIdle'
  const modeBtn = document.getElementById('modeBtn');
  const chargingBtn = document.getElementById('chargingBtn');
  const nightBtn = document.getElementById('nightChargingBtn');

  if (mode === 'idle') {
    // Idle ON => charging OFF
    if (chargingBtn && chargingBtn.classList.contains('on')) {
      safeUpdateButtonState('chargingBtn', false, 'fa-stop', 'fa-bolt', translations.charging, translations.stopped);
    }
    if (modeBtn && !modeBtn.classList.contains('on')) {
      safeUpdateButtonState('modeBtn', true, 'fa-pause', 'fa-play', translations.idle, translations.automatic);
    }
  } else if (mode === 'forced') {
    // Forced charging ON => Idle OFF
    if (modeBtn && modeBtn.classList.contains('on')) {
      safeUpdateButtonState('modeBtn', false, 'fa-pause', 'fa-play', translations.idle, translations.automatic);
    }
    if (chargingBtn && !chargingBtn.classList.contains('on')) {
      safeUpdateButtonState('chargingBtn', true, 'fa-stop', 'fa-bolt', translations.charging, translations.stopped);
    }
  } else if (mode === 'standard') {
    // Standard => both idle & forced OFF
    if (modeBtn && modeBtn.classList.contains('on')) {
      safeUpdateButtonState('modeBtn', false, 'fa-pause', 'fa-play', translations.idle, translations.automatic);
    }
    if (chargingBtn && chargingBtn.classList.contains('on')) {
      safeUpdateButtonState('chargingBtn', false, 'fa-stop', 'fa-bolt', translations.charging, translations.stopped);
    }
  }
}

// When polling, only update UI if no recent user action to prevent flicker.
function safeUpdateButtonState(id, isOn, activeIcon, inactiveIcon, activeText, inactiveText) {
  if (getLastActionAgeMs() < USER_ACTION_SUPPRESS_MS) return;
  updateButtonState(id, isOn, activeIcon, inactiveIcon, activeText, inactiveText);
}

const translations = window.AppData.translations || {};
const cheapestPeriods = window.AppData.cheapestPeriods || [];
const marketPrices = window.AppData.marketPrices || [];
const scheduledChargingPeriods = window.AppData.scheduledChargingPeriods || [];
const appCurrentTime = window.AppData.currentTime || Date.now();
let nightIdleEnabled = window.AppData.nightChargingIdle ?? false;
let nightIdleActive = window.AppData.nightIdleActive ?? false;
let nightStartHour = window.AppData.nightStartHour ?? 22;
let nightEndHour = window.AppData.nightEndHour ?? 6;
const createdCharts = {};
let lastStatusData = null;

function normalizeNumber(value) {
  const num = Number(value);
  return Number.isFinite(num) ? num : null;
}

function getStoredPriceMode() {
  try {
    const stored = (localStorage.getItem(PRICE_MODE_KEY) || '').toUpperCase();
    return stored === PRICE_MODE_BRUTTO ? PRICE_MODE_BRUTTO : PRICE_MODE_NETTO;
  } catch (e) {
    return PRICE_MODE_NETTO;
  }
}

function setPriceMode(mode, persist = true) {
  priceMode = mode === PRICE_MODE_BRUTTO ? PRICE_MODE_BRUTTO : PRICE_MODE_NETTO;
  if (persist) {
    try { localStorage.setItem(PRICE_MODE_KEY, priceMode); } catch (e) { /* ignore */ }
  }
  const toggle = document.getElementById('priceModeToggle');
  if (toggle) toggle.checked = priceMode === PRICE_MODE_BRUTTO;
  renderAllPrices();
}

function getDisplayedPrice(item) {
  if (!item) return null;
  const brutto = normalizeNumber(item.displayPriceBruttoCt);
  const netto = normalizeNumber(item.displayPriceNettoCt);
  const fallback = normalizeNumber(item.marketPrice ?? item.price);
  if (priceMode === PRICE_MODE_BRUTTO) {
    return brutto ?? fallback ?? netto;
  }
  return netto ?? fallback ?? brutto;
}

function formatPrice(value) {
  return value != null ? value.toFixed(2) : 'N/A';
}

function formatDateTime(timestamp) {
  if (!timestamp) return 'N/A';
  try {
    if (window.luxon?.DateTime) {
      const dt = luxon.DateTime.fromMillis(timestamp);
      return dt.isValid ? dt.toFormat('dd.MM.yyyy HH:mm:ss') : 'N/A';
    }
  } catch (e) { /* ignore */ }
  return new Date(timestamp).toLocaleString();
}

function getCheapestMarketPrice() {
  let cheapest = null;
  let cheapestValue = null;
  marketPrices.forEach(p => {
    const val = normalizeNumber(p.marketPrice);
    if (val == null) return;
    if (cheapestValue == null || val < cheapestValue) {
      cheapestValue = val;
      cheapest = p;
    }
  });
  return cheapest;
}

function renderCheapestPriceCard() {
  const cheapest = getCheapestMarketPrice();
  if (!cheapest) return;
  const startEl = document.getElementById('cheapestStartTime');
  const endEl = document.getElementById('cheapestEndTime');
  const priceEl = document.getElementById('cheapestPriceValue');
  const unitEl = document.getElementById('cheapestPriceUnit');

  if (startEl) startEl.textContent = formatDateTime(cheapest.startTimestamp);
  if (endEl) endEl.textContent = formatDateTime(cheapest.endTimestamp);
  if (priceEl) priceEl.textContent = formatPrice(getDisplayedPrice(cheapest));
  if (unitEl) unitEl.textContent = translations.centPerKwh || 'cent/kWh';
}

function renderTable(tbodyId, data, highlightCurrent = false) {
  const tbody = document.getElementById(tbodyId);
  if (!tbody) return;
  const now = appCurrentTime || Date.now();
  const rows = (data || []).map(item => {
    const highlight = highlightCurrent
      && item.startTimestamp != null
      && item.endTimestamp != null
      && item.startTimestamp <= now
      && item.endTimestamp >= now;
    return `<tr${highlight ? ' class="highlight"' : ''}>
      <td>${formatDateTime(item.startTimestamp)}</td>
      <td>${formatDateTime(item.endTimestamp)}</td>
      <td>${formatPrice(getDisplayedPrice(item))}</td>
    </tr>`;
  }).join('');
  tbody.innerHTML = rows;
}

function getYAxisTitle() {
  const base = translations.price || 'Price';
  const modeLabel = priceMode === PRICE_MODE_BRUTTO
    ? (translations.priceModeBruttoLabel || 'Total (gross)')
    : (translations.priceModeNettoLabel || 'Market (net)');
  return `${base} - ${modeLabel}`;
}

// Create a chart once (idempotent)
function createChartOnce(id, type, labels, data, options) {
  if (createdCharts[id]) {
    console.log(`Chart "${id}" wurde bereits erstellt`);
    return createdCharts[id];
  }

  const canvas = document.getElementById(id);
  if (!canvas) {
    console.error(`Canvas mit ID "${id}" nicht gefunden`);
    return;
  }

  try {
    const chart = new Chart(canvas.getContext('2d'), {
      type,
      data: {
        labels,
        datasets: [{
          label: translations.centPerKwh || 'cent/kWh',
          data,
          backgroundColor: type === 'line' ? 'rgba(102, 217, 232, 0.2)' : 'rgba(102, 217, 232, 0.6)',
          borderColor: '#66d9e8',
          borderWidth: 1,
          fill: type === 'line'
        }]
      },
      options
    });
    createdCharts[id] = chart;
    console.log(`Chart "${id}" erfolgreich erstellt mit ${labels.length} Datenpunkten`);
    return chart;
  } catch (error) {
    console.error(`Fehler beim Erstellen des Charts "${id}":`, error);
  }
}

function parseHourInput(inputEl, fallback) {
  if (!inputEl) return fallback;
  const val = parseInt((inputEl.value || '').trim(), 10);
  return Number.isFinite(val) && val >= 0 && val <= 23 ? val : fallback;
}

function isHourValid(val) {
  return Number.isFinite(val) && val >= 0 && val <= 23;
}

function updateNightWindowStatus(message, isError = false) {
  const statusEl = document.getElementById('nightWindowStatus');
  if (!statusEl) return;
  statusEl.textContent = message;
  statusEl.classList.remove('text-muted');
  statusEl.classList.toggle('text-danger', isError);
  statusEl.classList.toggle('text-success', !isError);
}

function getNightWindowFromInputs() {
  const startInput = document.getElementById('nightStartInput');
  const endInput = document.getElementById('nightEndInput');
  return {
    startHour: parseHourInput(startInput, nightStartHour),
    endHour: parseHourInput(endInput, nightEndHour)
  };
}

function getValidatedNightWindow() {
  const nightStartInput = document.getElementById('nightStartInput');
  const nightEndInput = document.getElementById('nightEndInput');
  const rawStart = parseInt((nightStartInput?.value || '').trim(), 10);
  const rawEnd = parseInt((nightEndInput?.value || '').trim(), 10);
  if (!isHourValid(rawStart) || !isHourValid(rawEnd)) {
    updateNightWindowStatus(translations.nightWindowInvalidHours || 'Please enter hours between 0 and 23.', true);
    return null;
  }
  return getNightWindowFromInputs();
}

// Document ready
document.addEventListener("DOMContentLoaded", function () {
  console.log("AppData geladen:", window.AppData);

  priceMode = getStoredPriceMode();
  const priceModeToggle = document.getElementById('priceModeToggle');
  if (priceModeToggle) {
    priceModeToggle.checked = priceMode === PRICE_MODE_BRUTTO;
    priceModeToggle.addEventListener('change', () => {
      setPriceMode(priceModeToggle.checked ? PRICE_MODE_BRUTTO : PRICE_MODE_NETTO);
    });
  }

  // Initialize tooltips
  const tooltipTriggerList = [].slice.call(document.querySelectorAll('[data-bs-toggle="tooltip"]'));
  tooltipTriggerList.forEach(el => new bootstrap.Tooltip(el));

  // Chart options
  const chartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      tooltip: {
        backgroundColor: 'rgba(102, 217, 232, 0.8)',
        titleColor: '#fff',
        bodyColor: '#fff'
      },
      legend: {
        labels: { color: '#f0f0f0' }
      }
    },
    scales: {
      x: {
        type: 'time',
        time: {
          unit: 'hour',
          tooltipFormat: 'dd LLLL HH:mm',
          displayFormats: { hour: 'dd LLLL HH:mm' }
        },
        title: { display: true, text: translations.startTime || 'Start Time', color: '#f0f0f0' },
        ticks: { color: '#f0f0f0' },
        grid: { color: 'rgba(255, 255, 255, 0.1)' }
      },
      y: {
        beginAtZero: true,
        title: { display: true, text: getYAxisTitle(), color: '#f0f0f0' },
        ticks: { color: '#f0f0f0' },
        grid: { color: 'rgba(255, 255, 255, 0.1)' }
      }
    }
  };

  // Toggle between table and chart
  function toggleView(buttonId, tableId, chartContainerId, chartId, chartType, dataSet) {
    const button = document.getElementById(buttonId);
    const table = document.getElementById(tableId);
    const chartContainer = document.getElementById(chartContainerId);
    const textSpan = button?.querySelector('span');

    if (!button || !table || !chartContainer) {
      console.error(`Elemente fehlen: button=${buttonId}, table=${tableId}, chart=${chartContainerId}`);
      return;
    }

    // Initial visibility: table visible, chart hidden
    table.style.display = 'block';
    chartContainer.style.display = 'none';

    button.addEventListener('click', () => {
      const showChart = chartContainer.style.display === 'none';

      table.style.display = showChart ? 'none' : 'block';
      chartContainer.style.display = showChart ? 'block' : 'none';

      if (textSpan) {
        textSpan.textContent = showChart ? (translations.table || 'Table') : (translations.chart || 'Chart');
      }

      if (showChart && !createdCharts[chartId]) {
        console.log(`Erstelle Chart "${chartId}" mit Daten:`, dataSet);
        if (!Array.isArray(dataSet)) {
          console.error(`dataSet für "${chartId}" ist kein Array:`, dataSet);
          return;
        }
        if (dataSet.length === 0) {
          console.warn(`dataSet für "${chartId}" ist leer`);
          return;
        }
        try {
          const labels = dataSet.map(p => new Date(p.startTimestamp));
          const data = dataSet.map(p => getDisplayedPrice(p));
          createChartOnce(chartId, chartType, labels, data, chartOptions);
          chartOptions.scales.y.title.text = getYAxisTitle();
        } catch (error) {
          console.error(`Fehler beim Verarbeiten der Daten für "${chartId}":`, error);
        }
      }
    });
  }

  // Toggle initialization
  toggleView('toggleCheapestPeriods', 'cheapestPeriodsTable', 'cheapestPeriodsChartContainer', 'cheapestPeriodsChart', 'bar', cheapestPeriods);
  toggleView('toggleMarketPrices', 'marketPricesTable', 'marketPricesChartContainer', 'marketPricesChart', 'line', marketPrices);
  toggleView('toggleChargingSchedule', 'chargingScheduleTable', 'chargingScheduleChartContainer', 'chargingScheduleChart', 'bar', scheduledChargingPeriods);

  const nightStartInput = document.getElementById('nightStartInput');
  const nightEndInput = document.getElementById('nightEndInput');
  if (nightStartInput && nightEndInput) {
    nightStartInput.value = nightStartHour;
    nightEndInput.value = nightEndHour;
  }
  updateNightIdleState(nightIdleEnabled, nightIdleActive);

  // Fetch and refresh status
  fetchCurrentStatus();
  setInterval(fetchCurrentStatus, 10000);
  setInterval(() => window.location.reload(), 65000);

  renderAllPrices();
});

// Fetch status
function fetchCurrentStatus() {
  fetch('/current-status', { method: 'GET' })
    .then(response => response.json())
    .then(data => {
      lastStatusData = data;
      safeUpdateButtonState('modeBtn', data.currentMode === 'idle', 'fa-pause', 'fa-play', translations.idle, translations.automatic);
      if (typeof data.nightChargingIdle === 'boolean') nightIdleEnabled = data.nightChargingIdle;
      if (typeof data.nightIdleActive === 'boolean') nightIdleActive = data.nightIdleActive;
      updateNightIdleState(nightIdleEnabled, nightIdleActive);
      safeUpdateButtonState('chargingBtn', data.isCharging, 'fa-stop', 'fa-bolt', translations.charging, translations.stopped);

      if (typeof data.nightStartHour === 'number') {
        nightStartHour = data.nightStartHour;
        const startInput = document.getElementById('nightStartInput');
        if (startInput && document.activeElement !== startInput) startInput.value = data.nightStartHour;
      }
      if (typeof data.nightEndHour === 'number') {
        nightEndHour = data.nightEndHour;
        const endInput = document.getElementById('nightEndInput');
        if (endInput && document.activeElement !== endInput) endInput.value = data.nightEndHour;
      }

      const chargingBtn = document.getElementById('chargingBtn');
      const currentPriceValue = getDisplayedStatusPrice(data);
      const currentPrice = currentPriceValue != null ? currentPriceValue.toFixed(2) + ' ' + (translations.centPerKwh || 'cent/kWh') : translations.noCurrentPriceInfo;
      const dropRate = data.dropRate != null ? data.dropRate.toFixed(2) + ' %/h' : 'N/A';
      const tooltipText = `${translations.chargingButtonTooltip}<br><strong>${translations.currentPriceLabel}:</strong> ${currentPrice}<br><strong>${translations.dropRateLabel}:</strong> ${dropRate}`;
      chargingBtn.setAttribute('data-bs-title', tooltipText);

      const tooltip = bootstrap.Tooltip.getInstance(chargingBtn);
      if (tooltip) tooltip.dispose();
      new bootstrap.Tooltip(chargingBtn);
    })
    .catch(error => console.error('Fehler beim Abrufen des Status:', error));
}

function getDisplayedStatusPrice(status) {
  if (!status) return null;
  const brutto = normalizeNumber(status.displayPriceBruttoCt);
  const netto = normalizeNumber(status.displayPriceNettoCt);
  const fallback = normalizeNumber(status.currentPrice);
  if (priceMode === PRICE_MODE_BRUTTO) {
    return brutto ?? fallback ?? netto;
  }
  return netto ?? fallback ?? brutto;
}

function updateCurrentPriceTooltip() {
  const chargingBtn = document.getElementById('chargingBtn');
  if (!chargingBtn || !lastStatusData) return;
  const currentPriceValue = getDisplayedStatusPrice(lastStatusData);
  const currentPrice = currentPriceValue != null ? currentPriceValue.toFixed(2) + ' ' + (translations.centPerKwh || 'cent/kWh') : translations.noCurrentPriceInfo;
  const dropRate = lastStatusData.dropRate != null ? lastStatusData.dropRate.toFixed(2) + ' %/h' : 'N/A';
  const tooltipText = `${translations.chargingButtonTooltip}<br><strong>${translations.currentPriceLabel}:</strong> ${currentPrice}<br><strong>${translations.dropRateLabel}:</strong> ${dropRate}`;
  chargingBtn.setAttribute('data-bs-title', tooltipText);
  const tooltip = bootstrap.Tooltip.getInstance(chargingBtn);
  if (tooltip) tooltip.dispose();
  new bootstrap.Tooltip(chargingBtn);
}

function updateChart(chartId, dataSet) {
  const chart = createdCharts[chartId];
  if (!chart || !Array.isArray(dataSet)) return;
  chart.data.labels = dataSet.map(p => new Date(p.startTimestamp));
  chart.data.datasets[0].data = dataSet.map(p => getDisplayedPrice(p));
  chart.data.datasets[0].label = translations.centPerKwh || 'cent/kWh';
  chart.options.scales.y.title.text = getYAxisTitle();
  chart.update();
}

function renderAllPrices() {
  renderCheapestPriceCard();
  renderTable('cheapestPeriodsTbody', cheapestPeriods, true);
  renderTable('marketPricesTbody', marketPrices, false);
  renderTable('chargingScheduleTbody', scheduledChargingPeriods, false);
  updateChart('cheapestPeriodsChart', cheapestPeriods);
  updateChart('marketPricesChart', marketPrices);
  updateChart('chargingScheduleChart', scheduledChargingPeriods);
  updateCurrentPriceTooltip();
}

// Update button state
function updateButtonState(btnId, isActive, activeIcon, inactiveIcon, activeText, inactiveText) {
  const btn = document.getElementById(btnId);
  if (!btn) return;

  const icon = btn.querySelector('i');
  const text = btn.querySelector('span:not(.status-indicator)');
  const indicator = btn.querySelector('.status-indicator');

  if (isActive) {
    btn.classList.remove('off');
    btn.classList.add('on');
    icon.classList.remove(inactiveIcon);
    icon.classList.add(activeIcon);
    text.textContent = activeText;
    indicator.classList.remove('inactive');
    indicator.classList.add('active');
  } else {
    btn.classList.remove('on');
    btn.classList.add('off');
    icon.classList.remove(activeIcon);
    icon.classList.add(inactiveIcon);
    text.textContent = inactiveText;
    indicator.classList.remove('active');
    indicator.classList.add('inactive');
  }
}

function updateNightIdleState(enabled, active) {
  const btn = document.getElementById('nightChargingBtn');
  if (!btn) return;

  const indicator = btn.querySelector('.status-indicator');
  const text = btn.querySelector('span:not(.status-indicator)');

  if (active) {
    btn.classList.remove('off');
    btn.classList.add('on');
  } else {
    btn.classList.remove('on');
    btn.classList.add('off');
  }

  if (text) text.textContent = translations.nightIdle || 'Night Idle';

  if (indicator) {
    indicator.classList.remove('active', 'enabled', 'inactive');
    indicator.classList.add(active ? 'active' : (enabled ? 'enabled' : 'inactive'));
  }
}

// Execute action
function handleAction(url, method, body = null, onSuccess = null, onFailure = null) {
  showLoader();
  fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : null
  })
    .then(response => response.json())
    .then(data => {
      if (data.success) {
        if (typeof onSuccess === 'function') onSuccess(data);
        setTimeout(() => {
          window.location.reload();
          hideLoader();
        }, 6000);
      } else {
        if (typeof onFailure === 'function') onFailure(data);
        alert(`Aktion fehlgeschlagen: ${data.message}`);
        hideLoader();
      }
    })
    .catch(error => {
      console.error('Fehler bei der Aktion:', error);
      if (typeof onFailure === 'function') onFailure({ success: false, message: error?.message });
      alert('Fehler bei der Ausführung der Aktion');
      hideLoader();
    });
}

// Show/hide loader
function showLoader(){
  var o=document.getElementById('loaderOverlay')||document.getElementById('loader');
  if(o){
    if(o.id==='loaderOverlay'){ o.classList.add('show'); }
    else { o.style.display='block'; }
  }
}

function hideLoader(){
  var o=document.getElementById('loaderOverlay')||document.getElementById('loader');
  if(o){
    if(o.id==='loaderOverlay'){ o.classList.remove('show'); }
    else { o.style.display='none'; }
  }
}

// Debounce helper
function debounce(func, wait) {
  let timeout;
  return function (...args) {
    clearTimeout(timeout);
    timeout = setTimeout(() => func.apply(this, args), wait);
  };
}

// Debounced functions
const debouncedToggleMode = debounce(() => {
  const btn = document.getElementById('modeBtn');
  const nextMode = btn.classList.contains('on') ? 'standard' : 'idle';
  setLastAction(nextMode);
  setExclusiveModeUI(nextMode);
  handleAction('/toggle-mode', 'POST', {
    mode: nextMode,
    force: btn.classList.contains('on')   // when switching back to automatic => force=true
  });
}, 300);

const debouncedToggleNightCharging = debounce(() => {
  const btn = document.getElementById('nightChargingBtn');
  const next = !nightIdleEnabled;
  setLastAction(next ? 'nightIdle' : 'standard');
  setExclusiveModeUI(next ? 'nightIdle' : 'standard');
  if (!next) nightIdleActive = false;
  updateNightIdleState(next, nightIdleActive);

  const windowValues = getValidatedNightWindow();
  if (!windowValues) return;

  handleAction('/toggle-night-charging', 'POST', { nightChargingIdle: next, ...windowValues }, () => {
    nightIdleEnabled = next;
    const { startHour, endHour } = windowValues;
    updateNightWindowStatus(`${translations.nightWindowStatusSaved || 'Saved'}: ${String(startHour).padStart(2, '0')}:00 - ${String(endHour).padStart(2, '0')}:00`);
  });
}, 300);

const debouncedToggleCharging = debounce(() => {
  const btn = document.getElementById('chargingBtn');
  if (!btn.classList.contains('on')) {
    setLastAction('forced');
    setExclusiveModeUI('forced');
    confirmStartCharging();
  } else {
    setLastAction('standard');
    setExclusiveModeUI('standard');
    handleAction('/toggle-charging', 'POST', { charging: 'stop' });
  }
}, 300);

// Charging confirmation
function confirmStartCharging() {
  const modal = new bootstrap.Modal(document.getElementById('confirmationModal'));
  modal.show();
  const timeout = setTimeout(() => modal.hide(), 5000);

  document.getElementById('confirmStart').onclick = () => {
    clearTimeout(timeout);
    handleAction('/toggle-charging', 'POST', { charging: 'start' });
    modal.hide();
  };

  document.getElementById('cancelStart').onclick = () => {
    clearTimeout(timeout);
    modal.hide();
    hideLoader();
  };
}

// Show tab
function showTab(tabId) {
  document.querySelectorAll('.tab-pane').forEach(tab => {
    tab.classList.remove('show', 'active');
  });
  const tab = document.getElementById(tabId);
  if (tab) tab.classList.add('show', 'active');
}
