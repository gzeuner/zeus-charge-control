const LAST_ACTION_KEY = 'tt_last_action_ts';
const LAST_MODE_KEY = 'tt_last_mode';
const PRICE_MODE_KEY = 'priceMode';
const PRICE_MODE_NETTO = 'NETTO';
const PRICE_MODE_BRUTTO = 'BRUTTO';
const THEME_KEY = 'chargeControlTheme';
const UI_STATE_KEY = 'chargeControlUiState';
const USER_ACTION_SUPPRESS_MS = 5000;
const DEFAULT_DATA_TAB = 'scheduled-charging-periods';
const VALID_DATA_TABS = ['scheduled-charging-periods', 'cheapest-periods', 'market-prices'];

const translations = window.AppData?.translations || {};
const cheapestPeriods = window.AppData?.cheapestPeriods || [];
const marketPrices = window.AppData?.marketPrices || [];
const scheduledChargingPeriods = window.AppData?.scheduledChargingPeriods || [];

let priceMode = PRICE_MODE_NETTO;
let nightIdleEnabled = window.AppData?.nightChargingIdle ?? false;
let nightIdleActive = window.AppData?.nightIdleActive ?? false;
let nightStartHour = window.AppData?.nightStartHour ?? 22;
let nightEndHour = window.AppData?.nightEndHour ?? 6;
let lastStatusData = null;

const createdCharts = {};

function nowMs() {
  return Date.now();
}

function setLastAction(mode) {
  try {
    localStorage.setItem(LAST_ACTION_KEY, String(nowMs()));
    if (mode) localStorage.setItem(LAST_MODE_KEY, mode);
  } catch (error) {
    console.debug('Could not persist last action', error);
  }
}

function getLastActionAgeMs() {
  try {
    const timestamp = Number(localStorage.getItem(LAST_ACTION_KEY) || '0');
    return timestamp > 0 ? nowMs() - timestamp : Number.POSITIVE_INFINITY;
  } catch (error) {
    return Number.POSITIVE_INFINITY;
  }
}

function safeUpdateButtonState(id, isOn, activeIcon, inactiveIcon, activeText, inactiveText) {
  if (getLastActionAgeMs() < USER_ACTION_SUPPRESS_MS) return;
  updateButtonState(id, isOn, activeIcon, inactiveIcon, activeText, inactiveText);
}

function normalizeNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function getStoredPriceMode() {
  try {
    const stored = (localStorage.getItem(PRICE_MODE_KEY) || '').toUpperCase();
    return stored === PRICE_MODE_BRUTTO ? PRICE_MODE_BRUTTO : PRICE_MODE_NETTO;
  } catch (error) {
    return PRICE_MODE_NETTO;
  }
}

function getStoredTheme() {
  try {
    const stored = localStorage.getItem(THEME_KEY);
    return ['default', 'dark', 'energy', 'solar', 'ember'].includes(stored) ? stored : 'default';
  } catch (error) {
    return 'default';
  }
}

function getStoredUiState() {
  try {
    const parsed = JSON.parse(localStorage.getItem(UI_STATE_KEY) || '{}');
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch (error) {
    return {};
  }
}

function setStoredUiState(nextState) {
  try {
    localStorage.setItem(UI_STATE_KEY, JSON.stringify(nextState));
  } catch (error) {
    console.debug('Could not persist UI state', error);
  }
}

function getStoredActiveTab() {
  const storedTab = getStoredUiState().activeTab;
  return VALID_DATA_TABS.includes(storedTab) ? storedTab : DEFAULT_DATA_TAB;
}

function persistActiveTab(tabId) {
  if (!VALID_DATA_TABS.includes(tabId)) return;
  const currentState = getStoredUiState();
  const nextState = {
    ...currentState,
    activeTab: tabId,
    viewModes: currentState.viewModes && typeof currentState.viewModes === 'object'
      ? currentState.viewModes
      : {}
  };
  setStoredUiState(nextState);
}

function getStoredViewMode(viewKey) {
  const viewModes = getStoredUiState().viewModes;
  const storedMode = viewModes && typeof viewModes === 'object' ? viewModes[viewKey] : null;
  return storedMode === 'chart' ? 'chart' : 'table';
}

function persistViewMode(viewKey, mode) {
  if (!viewKey) return;
  const resolvedMode = mode === 'chart' ? 'chart' : 'table';
  const currentState = getStoredUiState();
  const currentViewModes = currentState.viewModes && typeof currentState.viewModes === 'object'
    ? currentState.viewModes
    : {};
  const nextState = {
    ...currentState,
    viewModes: {
      ...currentViewModes,
      [viewKey]: resolvedMode
    }
  };
  setStoredUiState(nextState);
}

function applyTheme(theme, persist = true) {
  const resolvedTheme = ['default', 'dark', 'energy', 'solar', 'ember'].includes(theme) ? theme : 'default';
  document.documentElement.setAttribute('data-theme', resolvedTheme);
  if (persist) {
    try {
      localStorage.setItem(THEME_KEY, resolvedTheme);
    } catch (error) {
      console.debug('Could not persist theme', error);
    }
  }

  const selector = document.getElementById('themeSelector');
  if (selector) selector.value = resolvedTheme;
  syncThemeDropdownUI(resolvedTheme);
  refreshChartsForTheme();
}

function syncThemeDropdownUI(theme) {
  const selector = document.getElementById('themeSelector');
  const label = document.getElementById('themeDropdownLabel');

  if (selector) {
    selector.value = theme;
    const selectedOption = selector.options[selector.selectedIndex];
    if (label) label.textContent = selectedOption ? selectedOption.textContent.trim() : theme;
  } else if (label) {
    label.textContent = theme;
  }

  document.querySelectorAll('[data-theme-option]').forEach(button => {
    const isActive = button.getAttribute('data-theme-option') === theme;
    button.classList.toggle('active', isActive);
    button.setAttribute('aria-pressed', String(isActive));
  });
}

function getCssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function getChartPalette(type) {
  return {
    backgroundColor: type === 'line' ? getCssVar('--chart-fill') : getCssVar('--chart-fill'),
    borderColor: getCssVar('--chart-line'),
    tickColor: getCssVar('--chart-text'),
    gridColor: getCssVar('--chart-grid')
  };
}

function buildChartOptions() {
  const palette = getChartPalette('line');
  return {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        labels: {
          color: palette.tickColor
        }
      },
      tooltip: {
        backgroundColor: getCssVar('--surface-strong'),
        titleColor: getCssVar('--heading'),
        bodyColor: getCssVar('--text')
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
        title: {
          display: true,
          text: translations.startTime || 'Start Time',
          color: palette.tickColor
        },
        ticks: { color: palette.tickColor },
        grid: { color: palette.gridColor }
      },
      y: {
        beginAtZero: true,
        title: {
          display: true,
          text: getYAxisTitle(),
          color: palette.tickColor
        },
        ticks: { color: palette.tickColor },
        grid: { color: palette.gridColor }
      }
    }
  };
}

function refreshChartsForTheme() {
  Object.values(createdCharts).forEach(chart => {
    const palette = getChartPalette(chart.config.type);
    chart.data.datasets[0].backgroundColor = palette.backgroundColor;
    chart.data.datasets[0].borderColor = palette.borderColor;
    chart.options.plugins.legend.labels.color = palette.tickColor;
    chart.options.plugins.tooltip.backgroundColor = getCssVar('--surface-strong');
    chart.options.plugins.tooltip.titleColor = getCssVar('--heading');
    chart.options.plugins.tooltip.bodyColor = getCssVar('--text');
    chart.options.scales.x.title.color = palette.tickColor;
    chart.options.scales.x.ticks.color = palette.tickColor;
    chart.options.scales.x.grid.color = palette.gridColor;
    chart.options.scales.y.title.color = palette.tickColor;
    chart.options.scales.y.title.text = getYAxisTitle();
    chart.options.scales.y.ticks.color = palette.tickColor;
    chart.options.scales.y.grid.color = palette.gridColor;
    chart.update();
  });
}

function setPriceMode(mode, persist = true) {
  priceMode = mode === PRICE_MODE_BRUTTO ? PRICE_MODE_BRUTTO : PRICE_MODE_NETTO;
  if (persist) {
    try {
      localStorage.setItem(PRICE_MODE_KEY, priceMode);
    } catch (error) {
      console.debug('Could not persist price mode', error);
    }
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
  return priceMode === PRICE_MODE_BRUTTO
    ? (brutto ?? fallback ?? netto)
    : (netto ?? fallback ?? brutto);
}

function formatPrice(value) {
  return value != null ? value.toFixed(2) : 'N/A';
}

function formatDateTime(timestamp) {
  if (!timestamp) return 'N/A';
  try {
    if (window.luxon?.DateTime) {
      const dateTime = luxon.DateTime.fromMillis(timestamp);
      return dateTime.isValid ? dateTime.toFormat('dd.MM.yyyy HH:mm:ss') : 'N/A';
    }
  } catch (error) {
    console.debug('Could not format time via luxon', error);
  }
  return new Date(timestamp).toLocaleString();
}

function getCheapestMarketPrice() {
  let cheapest = null;
  let cheapestValue = null;
  marketPrices.forEach(item => {
    const value = normalizeNumber(item.marketPrice);
    if (value == null) return;
    if (cheapestValue == null || value < cheapestValue) {
      cheapestValue = value;
      cheapest = item;
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

  const now = Date.now();
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
    ? (translations.priceModeBruttoLabel || 'Total')
    : (translations.priceModeNettoLabel || 'Market');
  return `${base} - ${modeLabel}`;
}

function createChartOnce(id, type, labels, data) {
  if (createdCharts[id]) return createdCharts[id];

  const canvas = document.getElementById(id);
  if (!canvas) return null;

  const palette = getChartPalette(type);
  const chart = new Chart(canvas.getContext('2d'), {
    type,
    data: {
      labels,
      datasets: [{
        label: translations.centPerKwh || 'cent/kWh',
        data,
        backgroundColor: palette.backgroundColor,
        borderColor: palette.borderColor,
        borderWidth: 2,
        fill: type === 'line',
        tension: type === 'line' ? 0.25 : 0
      }]
    },
    options: buildChartOptions()
  });

  createdCharts[id] = chart;
  return chart;
}

function parseHourInput(inputEl, fallback) {
  if (!inputEl) return fallback;
  const value = parseInt((inputEl.value || '').trim(), 10);
  return Number.isFinite(value) && value >= 0 && value <= 23 ? value : fallback;
}

function isHourValid(value) {
  return Number.isFinite(value) && value >= 0 && value <= 23;
}

function updateNightWindowStatus(message, isError = false) {
  const statusEl = document.getElementById('nightWindowStatus');
  if (!statusEl) return;
  statusEl.textContent = message;
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
  const startInput = document.getElementById('nightStartInput');
  const endInput = document.getElementById('nightEndInput');
  const rawStart = parseInt((startInput?.value || '').trim(), 10);
  const rawEnd = parseInt((endInput?.value || '').trim(), 10);

  if (!isHourValid(rawStart) || !isHourValid(rawEnd)) {
    updateNightWindowStatus(translations.nightWindowInvalidHours || 'Please enter hours between 0 and 23.', true);
    return null;
  }

  return getNightWindowFromInputs();
}

function updateToggleButton(button, chartVisible) {
  if (!button) return;
  const label = button.querySelector('span');
  const icon = button.querySelector('i');
  if (label) label.textContent = chartVisible ? (translations.table || 'Table') : (translations.chart || 'Chart');
  if (icon) {
    icon.classList.remove('fa-chart-column', 'fa-chart-line', 'fa-table');
    icon.classList.add(chartVisible ? 'fa-table' : 'fa-chart-column');
  }
}

function applyToggleViewState(button, table, chartContainer, chartId, chartType, dataSet, showChart) {
  table.style.display = showChart ? 'none' : 'block';
  chartContainer.style.display = showChart ? 'block' : 'none';
  updateToggleButton(button, showChart);

  if (showChart) {
    const labels = dataSet.map(item => new Date(item.startTimestamp));
    const data = dataSet.map(item => getDisplayedPrice(item));
    createChartOnce(chartId, chartType, labels, data);
    updateChart(chartId, dataSet);
  }
}

function toggleView(buttonId, tableId, chartContainerId, chartId, chartType, dataSet, viewKey) {
  const button = document.getElementById(buttonId);
  const table = document.getElementById(tableId);
  const chartContainer = document.getElementById(chartContainerId);
  if (!button || !table || !chartContainer) return;

  const initialShowChart = getStoredViewMode(viewKey) === 'chart';
  applyToggleViewState(button, table, chartContainer, chartId, chartType, dataSet, initialShowChart);

  button.addEventListener('click', () => {
    const showChart = chartContainer.style.display === 'none';
    applyToggleViewState(button, table, chartContainer, chartId, chartType, dataSet, showChart);
    persistViewMode(viewKey, showChart ? 'chart' : 'table');
  });
}

function getDisplayedStatusPrice(status) {
  if (!status) return null;
  const brutto = normalizeNumber(status.displayPriceBruttoCt);
  const netto = normalizeNumber(status.displayPriceNettoCt);
  const fallback = normalizeNumber(status.currentPrice);
  return priceMode === PRICE_MODE_BRUTTO
    ? (brutto ?? fallback ?? netto)
    : (netto ?? fallback ?? brutto);
}

function updateCurrentPriceTooltip() {
  const chargingBtn = document.getElementById('chargingBtn');
  if (!chargingBtn || !lastStatusData) return;

  const currentPriceValue = getDisplayedStatusPrice(lastStatusData);
  const currentPrice = currentPriceValue != null
    ? `${currentPriceValue.toFixed(2)} ${(translations.centPerKwh || 'cent/kWh')}`
    : translations.noCurrentPriceInfo;
  const dropRate = lastStatusData.dropRate != null ? `${lastStatusData.dropRate.toFixed(2)} %/h` : 'N/A';
  const estimatedTimeToTarget = lastStatusData.estimatedTimeToTarget != null
    ? `${lastStatusData.estimatedTimeToTarget.toFixed(2)} h`
    : 'N/A';
  const tooltipText = `${translations.chargingButtonTooltip}<br><strong>${translations.currentPriceLabel}:</strong> ${currentPrice}<br><strong>${translations.dropRateLabel}:</strong> ${dropRate}<br><strong>${translations.estimatedTimeToTargetLabel || 'Estimated Time to Target'}:</strong> ${estimatedTimeToTarget}`;

  chargingBtn.setAttribute('data-bs-title', tooltipText);
  const tooltip = bootstrap.Tooltip.getInstance(chargingBtn);
  if (tooltip) tooltip.dispose();
  bootstrap.Tooltip.getOrCreateInstance(chargingBtn);
}

function updateMetricValue(valueId, unitId, value, unitText) {
  const valueEl = document.getElementById(valueId);
  const unitEl = document.getElementById(unitId);
  if (!valueEl) return;

  if (value == null) {
    valueEl.textContent = 'N/A';
    if (unitEl) unitEl.style.display = 'none';
    return;
  }

  valueEl.textContent = value.toFixed(2);
  if (unitEl) {
    if (unitText) unitEl.textContent = unitText;
    unitEl.style.display = '';
  }
}

function updateWholeNumberValue(valueId, value) {
  const valueEl = document.getElementById(valueId);
  if (!valueEl) return;
  if (value == null) {
    valueEl.textContent = 'N/A';
    return;
  }
  valueEl.textContent = String(Math.round(value));
}

function updateStatusMetricCards() {
  if (!lastStatusData) return;

  const currentPriceValue = getDisplayedStatusPrice(lastStatusData);
  updateMetricValue('currentPriceValue', 'currentPriceUnit', currentPriceValue, translations.centPerKwh || 'cent/kWh');
  updateMetricValue('dropRateValue', 'dropRateUnit', normalizeNumber(lastStatusData.dropRate), '%/h');
  updateMetricValue(
    'estimatedTimeToTargetValue',
    'estimatedTimeToTargetUnit',
    normalizeNumber(lastStatusData.estimatedTimeToTarget),
    'h'
  );
  updateWholeNumberValue('capacityPercentValue', normalizeNumber(lastStatusData.stateOfCharge));
  updateWholeNumberValue('capacityRemainingWhValue', normalizeNumber(lastStatusData.remainingCapacityWh));
}

function updateChart(chartId, dataSet) {
  const chart = createdCharts[chartId];
  if (!chart) return;

  chart.data.labels = dataSet.map(item => new Date(item.startTimestamp));
  chart.data.datasets[0].data = dataSet.map(item => getDisplayedPrice(item));
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
  updateStatusMetricCards();
  updateCurrentPriceTooltip();
}

function updateButtonState(buttonId, isActive, activeIcon, inactiveIcon, activeText, inactiveText) {
  const button = document.getElementById(buttonId);
  if (!button) return;

  const icon = button.querySelector('i');
  const label = button.querySelector('[data-role="label"]');
  const indicator = button.querySelector('.status-indicator');

  button.classList.toggle('on', isActive);
  button.classList.toggle('off', !isActive);
  button.setAttribute('aria-pressed', String(isActive));

  if (icon) {
    icon.classList.remove(activeIcon, inactiveIcon);
    icon.classList.add(isActive ? activeIcon : inactiveIcon);
  }

  if (label) label.textContent = isActive ? activeText : inactiveText;
  if (indicator) {
    indicator.classList.toggle('active', isActive);
    indicator.classList.toggle('inactive', !isActive);
    indicator.classList.remove('enabled');
  }
}

function updateNightIdleState(enabled, active) {
  const button = document.getElementById('nightChargingBtn');
  if (!button) return;

  const indicator = button.querySelector('.status-indicator');
  const label = button.querySelector('[data-role="label"]');

  button.classList.toggle('on', active);
  button.classList.toggle('off', !active);
  button.setAttribute('aria-pressed', String(active));

  if (label) label.textContent = translations.nightIdle || 'Night Idle';
  if (indicator) {
    indicator.classList.remove('active', 'enabled', 'inactive');
    indicator.classList.add(active ? 'active' : (enabled ? 'enabled' : 'inactive'));
  }
}

function setExclusiveModeUI(mode) {
  const modeBtn = document.getElementById('modeBtn');
  const chargingBtn = document.getElementById('chargingBtn');

  if (mode === 'idle') {
    if (chargingBtn?.classList.contains('on')) {
      updateButtonState('chargingBtn', false, 'fa-stop', 'fa-bolt', translations.charging, translations.stopped);
    }
    if (modeBtn && !modeBtn.classList.contains('on')) {
      updateButtonState('modeBtn', true, 'fa-pause', 'fa-play', translations.idle, translations.automatic);
    }
  } else if (mode === 'forced') {
    if (modeBtn?.classList.contains('on')) {
      updateButtonState('modeBtn', false, 'fa-pause', 'fa-play', translations.idle, translations.automatic);
    }
    if (chargingBtn && !chargingBtn.classList.contains('on')) {
      updateButtonState('chargingBtn', true, 'fa-stop', 'fa-bolt', translations.charging, translations.stopped);
    }
  } else if (mode === 'standard') {
    if (modeBtn?.classList.contains('on')) {
      updateButtonState('modeBtn', false, 'fa-pause', 'fa-play', translations.idle, translations.automatic);
    }
    if (chargingBtn?.classList.contains('on')) {
      updateButtonState('chargingBtn', false, 'fa-stop', 'fa-bolt', translations.charging, translations.stopped);
    }
  }
}

function setBusyState(isBusy) {
  document.body.classList.toggle('is-loading', isBusy);
  document.querySelectorAll('[data-ui-action="true"]').forEach(element => {
    element.disabled = isBusy;
  });
}

function showLoader(text) {
  const overlay = document.getElementById('loaderOverlay');
  const loaderText = document.getElementById('loaderText');
  if (loaderText && text) loaderText.textContent = text;
  if (overlay) overlay.classList.add('show');
}

function hideLoader() {
  const overlay = document.getElementById('loaderOverlay');
  if (overlay) overlay.classList.remove('show');
}

function executeJsonAction(url, method, body = null, options = {}) {
  const {
    onSuccess = null,
    onFailure = null,
    reloadOnSuccess = true,
    successDelayMs = 6000
  } = options;

  setBusyState(true);
  showLoader();

  fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : null
  })
    .then(response => response.json())
    .then(data => {
      if (!data.success) {
        if (typeof onFailure === 'function') onFailure(data);
        alert(`Aktion fehlgeschlagen: ${data.message}`);
        setBusyState(false);
        hideLoader();
        return;
      }

      if (typeof onSuccess === 'function') onSuccess(data);

      if (reloadOnSuccess) {
        setTimeout(() => window.location.reload(), successDelayMs);
        return;
      }

      setBusyState(false);
      hideLoader();
    })
    .catch(error => {
      console.error('Action failed', error);
      if (typeof onFailure === 'function') onFailure({ success: false, message: error?.message });
      alert('Fehler bei der Ausfuehrung der Aktion');
      setBusyState(false);
      hideLoader();
    });
}

function fetchCurrentStatus() {
  fetch('/current-status', { method: 'GET' })
    .then(response => response.json())
    .then(data => {
      lastStatusData = data;
      safeUpdateButtonState('modeBtn', data.currentMode === 'idle', 'fa-pause', 'fa-play', translations.idle, translations.automatic);
      safeUpdateButtonState('chargingBtn', data.isCharging, 'fa-stop', 'fa-bolt', translations.charging, translations.stopped);

      if (typeof data.nightChargingIdle === 'boolean') nightIdleEnabled = data.nightChargingIdle;
      if (typeof data.nightIdleActive === 'boolean') nightIdleActive = data.nightIdleActive;
      updateNightIdleState(nightIdleEnabled, nightIdleActive);

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

      updateStatusMetricCards();
      updateCurrentPriceTooltip();
    })
    .catch(error => console.error('Status polling failed', error));
}

function confirmStartCharging() {
  const modalElement = document.getElementById('confirmationModal');
  if (!modalElement) {
    executeJsonAction('/toggle-charging', 'POST', { charging: 'start' });
    return;
  }

  const modal = new bootstrap.Modal(modalElement);
  modal.show();
  const timeout = setTimeout(() => modal.hide(), 5000);
  let confirmed = false;

  document.getElementById('confirmStart').onclick = () => {
    clearTimeout(timeout);
    confirmed = true;
    modal.hide();
    executeJsonAction('/toggle-charging', 'POST', { charging: 'start' });
  };

  document.getElementById('cancelStart').onclick = () => {
    clearTimeout(timeout);
    modal.hide();
    setExclusiveModeUI('standard');
    hideLoader();
  };

  modalElement.addEventListener('hidden.bs.modal', () => {
    if (!confirmed) {
      setExclusiveModeUI('standard');
    }
  }, { once: true });
}

function showTab(tabId) {
  const resolvedTabId = VALID_DATA_TABS.includes(tabId) ? tabId : DEFAULT_DATA_TAB;
  const targetButton = document.querySelector(`[data-bs-target="#${resolvedTabId}"]`);
  if (targetButton) {
    bootstrap.Tab.getOrCreateInstance(targetButton).show();
  }

  const select = document.getElementById('dataTabSelect');
  if (select) select.value = resolvedTabId;
  persistActiveTab(resolvedTabId);
}

function bindTabSync() {
  const select = document.getElementById('dataTabSelect');
  document.querySelectorAll('#priceTabs [data-bs-toggle="tab"]').forEach(button => {
    button.addEventListener('shown.bs.tab', event => {
      const targetId = event.target.getAttribute('data-bs-target')?.replace('#', '');
      if (select && targetId) select.value = targetId;
      if (targetId) persistActiveTab(targetId);
    });
  });
}

function debounce(fn, wait) {
  let timeout;
  return function debounced(...args) {
    clearTimeout(timeout);
    timeout = setTimeout(() => fn.apply(this, args), wait);
  };
}

const debouncedToggleMode = debounce(() => {
  const button = document.getElementById('modeBtn');
  if (!button) return;

  const nextMode = button.classList.contains('on') ? 'standard' : 'idle';
  setLastAction(nextMode);
  setExclusiveModeUI(nextMode);
  executeJsonAction('/toggle-mode', 'POST', {
    mode: nextMode,
    force: button.classList.contains('on')
  });
}, 300);

const debouncedToggleNightCharging = debounce(() => {
  const windowValues = getValidatedNightWindow();
  if (!windowValues) return;

  const next = !nightIdleEnabled;
  setLastAction(next ? 'nightIdle' : 'standard');
  if (!next) nightIdleActive = false;
  updateNightIdleState(next, nightIdleActive);

  executeJsonAction('/toggle-night-charging', 'POST', { nightChargingIdle: next, ...windowValues }, {
    onSuccess: () => {
      nightIdleEnabled = next;
      updateNightWindowStatus(`${translations.nightWindowStatusSaved || 'Saved'}: ${String(windowValues.startHour).padStart(2, '0')}:00 - ${String(windowValues.endHour).padStart(2, '0')}:00`);
    }
  });
}, 300);

const debouncedToggleCharging = debounce(() => {
  const button = document.getElementById('chargingBtn');
  if (!button) return;

  if (!button.classList.contains('on')) {
    setLastAction('forced');
    setExclusiveModeUI('forced');
    confirmStartCharging();
    return;
  }

  setLastAction('standard');
  setExclusiveModeUI('standard');
  executeJsonAction('/toggle-charging', 'POST', { charging: 'stop' });
}, 300);

window.debouncedToggleMode = debouncedToggleMode;
window.debouncedToggleNightCharging = debouncedToggleNightCharging;
window.debouncedToggleCharging = debouncedToggleCharging;
window.hideLoader = hideLoader;
window.showTab = showTab;

document.addEventListener('DOMContentLoaded', () => {
  priceMode = getStoredPriceMode();
  setPriceMode(priceMode, false);
  applyTheme(getStoredTheme(), false);

  const themeSelector = document.getElementById('themeSelector');
  if (themeSelector) {
    themeSelector.addEventListener('change', event => applyTheme(event.target.value));
  }

  document.querySelectorAll('[data-theme-option]').forEach(button => {
    button.addEventListener('click', () => {
      const nextTheme = button.getAttribute('data-theme-option');
      if (nextTheme) applyTheme(nextTheme);
    });
  });

  const priceModeToggle = document.getElementById('priceModeToggle');
  if (priceModeToggle) {
    priceModeToggle.checked = priceMode === PRICE_MODE_BRUTTO;
    priceModeToggle.addEventListener('change', () => {
      setPriceMode(priceModeToggle.checked ? PRICE_MODE_BRUTTO : PRICE_MODE_NETTO);
    });
  }

  const saveNightWindowBtn = document.getElementById('saveNightWindowBtn');
  if (saveNightWindowBtn) {
    saveNightWindowBtn.addEventListener('click', () => {
      const windowValues = getValidatedNightWindow();
      if (!windowValues) return;

      executeJsonAction('/night-charging-window', 'POST', windowValues, {
        reloadOnSuccess: false,
        onSuccess: () => {
          nightStartHour = windowValues.startHour;
          nightEndHour = windowValues.endHour;
          updateNightWindowStatus(`${translations.nightWindowStatusSaved || 'Saved'}: ${String(windowValues.startHour).padStart(2, '0')}:00 - ${String(windowValues.endHour).padStart(2, '0')}:00`);
        },
        onFailure: () => updateNightWindowStatus(translations.nightWindowSaveError || 'Saving night window failed', true)
      });
    });
  }

  document.querySelectorAll('[data-bs-toggle="tooltip"]').forEach(element => {
    bootstrap.Tooltip.getOrCreateInstance(element);
  });

  toggleView('toggleCheapestPeriods', 'cheapestPeriodsTable', 'cheapestPeriodsChartContainer', 'cheapestPeriodsChart', 'bar', cheapestPeriods, 'cheapest-periods');
  toggleView('toggleMarketPrices', 'marketPricesTable', 'marketPricesChartContainer', 'marketPricesChart', 'line', marketPrices, 'market-prices');
  toggleView('toggleChargingSchedule', 'chargingScheduleTable', 'chargingScheduleChartContainer', 'chargingScheduleChart', 'bar', scheduledChargingPeriods, 'scheduled-charging-periods');
  bindTabSync();
  showTab(getStoredActiveTab());

  const startInput = document.getElementById('nightStartInput');
  const endInput = document.getElementById('nightEndInput');
  if (startInput && endInput) {
    startInput.value = nightStartHour;
    endInput.value = nightEndHour;
  }

  updateNightIdleState(nightIdleEnabled, nightIdleActive);
  fetchCurrentStatus();
  setInterval(fetchCurrentStatus, 10000);
  setInterval(() => window.location.reload(), 65000);
  renderAllPrices();
});
