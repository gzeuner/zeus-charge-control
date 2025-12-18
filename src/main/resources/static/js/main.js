// Global variables

// ===== Luna: 'Last-click-wins' - UI state helpers =====
const LAST_ACTION_KEY = 'tt_last_action_ts';
const LAST_MODE_KEY   = 'tt_last_mode'; // 'idle' | 'standard' | 'forced' | 'nightIdle'

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
  } else if (mode === 'nightIdle') {
    if (nightBtn && !nightBtn.classList.contains('on')) {
      safeUpdateButtonState('nightChargingBtn', true, 'fa-moon', 'fa-moon', translations.nightIdle, translations.nightIdle);
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
let nightStartHour = window.AppData.nightStartHour ?? 22;
let nightEndHour = window.AppData.nightEndHour ?? 6;
const createdCharts = {};

// Create a chart once (idempotent)
function createChartOnce(id, type, labels, data, options) {
  if (createdCharts[id]) {
    console.log(`Chart "${id}" wurde bereits erstellt`);
    return;
  }

  const canvas = document.getElementById(id);
  if (!canvas) {
    console.error(`Canvas mit ID "${id}" nicht gefunden`);
    return;
  }

  try {
    new Chart(canvas.getContext('2d'), {
      type,
      data: {
        labels,
        datasets: [{
          label: 'cent/kWh',
          data,
          backgroundColor: type === 'line' ? 'rgba(102, 217, 232, 0.2)' : 'rgba(102, 217, 232, 0.6)',
          borderColor: '#66d9e8',
          borderWidth: 1,
          fill: type === 'line'
        }]
      },
      options
    });
    createdCharts[id] = true;
    console.log(`Chart "${id}" erfolgreich erstellt mit ${labels.length} Datenpunkten`);
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

function saveNightWindow(startHour, endHour) {
  showLoader();
  return fetch('/night-charging-window', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ startHour, endHour })
  })
    .then(response => response.json())
    .then(data => {
      hideLoader();
      if (data.success) {
        nightStartHour = startHour;
        nightEndHour = endHour;
        updateNightWindowStatus(`${translations.nightWindowStatusSaved || 'Saved'}: ${String(startHour).padStart(2, '0')}:00 - ${String(endHour).padStart(2, '0')}:00`);
      } else {
        updateNightWindowStatus(data.message || 'Saving night window failed', true);
      }
      return data.success;
    })
    .catch(error => {
      hideLoader();
      console.error('Error saving night window:', error);
      updateNightWindowStatus(translations.nightWindowSaveError || 'Saving night window failed', true);
      return false;
    });
}

// Document ready
document.addEventListener("DOMContentLoaded", function () {
  console.log("AppData geladen:", window.AppData);

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
        title: { display: true, text: translations.price || 'Price (cent/kWh)', color: '#f0f0f0' },
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
          const data = chartType === 'bar' ? dataSet.map(p => p.price || p.marketPrice) : dataSet.map(p => p.marketPrice);
          createChartOnce(chartId, chartType, labels, data, chartOptions);
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

  const saveNightWindowBtn = document.getElementById('saveNightWindow');
  if (saveNightWindowBtn) {
    saveNightWindowBtn.addEventListener('click', () => {
      const { startHour, endHour } = getNightWindowFromInputs();
      const rawStart = parseInt((nightStartInput?.value || '').trim(), 10);
      const rawEnd = parseInt((nightEndInput?.value || '').trim(), 10);
      if (!isHourValid(rawStart) || !isHourValid(rawEnd)) {
        updateNightWindowStatus(translations.nightWindowInvalidHours || 'Please enter hours between 0 and 23.', true);
        return;
      }
      saveNightWindow(startHour, endHour);
    });
  }

  // Fetch and refresh status
  fetchCurrentStatus();
  setInterval(fetchCurrentStatus, 10000);
  setInterval(() => window.location.reload(), 65000);
});

// Fetch status
function fetchCurrentStatus() {
  fetch('/current-status', { method: 'GET' })
    .then(response => response.json())
    .then(data => {
      safeUpdateButtonState('modeBtn', data.currentMode === 'idle', 'fa-pause', 'fa-play', translations.idle, translations.automatic);
      safeUpdateButtonState('nightChargingBtn', data.nightChargingIdle, 'fa-moon', 'fa-moon', translations.nightIdle, translations.nightIdle);
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
      const currentPrice = data.currentPrice != null ? data.currentPrice.toFixed(2) + ' cent/kWh' : translations.noCurrentPriceInfo;
      const dropRate = data.dropRate != null ? data.dropRate.toFixed(2) + ' %/h' : 'N/A';
      const tooltipText = `${translations.chargingButtonTooltip}<br><strong>${translations.currentPriceLabel}:</strong> ${currentPrice}<br><strong>${translations.dropRateLabel}:</strong> ${dropRate}`;
      chargingBtn.setAttribute('data-bs-title', tooltipText);

      const tooltip = bootstrap.Tooltip.getInstance(chargingBtn);
      if (tooltip) tooltip.dispose();
      new bootstrap.Tooltip(chargingBtn);
    })
    .catch(error => console.error('Fehler beim Abrufen des Status:', error));
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

// Execute action
function handleAction(url, method, body = null) {
  showLoader();
  fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : null
  })
    .then(response => response.json())
    .then(data => {
      if (data.success) {
        setTimeout(() => {
          window.location.reload();
          hideLoader();
        }, 6000);
      } else {
        alert(`Aktion fehlgeschlagen: ${data.message}`);
        hideLoader();
      }
    })
    .catch(error => {
      console.error('Fehler bei der Aktion:', error);
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
  const next = !btn.classList.contains('on');
  setLastAction(next ? 'nightIdle' : 'standard');
  setExclusiveModeUI(next ? 'nightIdle' : 'standard');

  const { startHour, endHour } = getNightWindowFromInputs();

  handleAction('/toggle-night-charging', 'POST', { nightChargingIdle: next, startHour, endHour });
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
