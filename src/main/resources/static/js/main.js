// Globale Variablen
const translations = window.AppData.translations || {};
const cheapestPeriods = window.AppData.cheapestPeriods || [];
const marketPrices = window.AppData.marketPrices || [];
const scheduledChargingPeriods = window.AppData.scheduledChargingPeriods || [];
const createdCharts = {};

// Funktion zum Erstellen eines Charts (nur einmalig)
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

// Dokument geladen
document.addEventListener("DOMContentLoaded", function () {
  console.log("AppData geladen:", window.AppData);

  // Tooltips initialisieren
  const tooltipTriggerList = [].slice.call(document.querySelectorAll('[data-bs-toggle="tooltip"]'));
  tooltipTriggerList.forEach(el => new bootstrap.Tooltip(el));

  // Chart-Optionen
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

  // Funktion zum Umschalten zwischen Tabelle und Chart
  function toggleView(buttonId, tableId, chartContainerId, chartId, chartType, dataSet) {
    const button = document.getElementById(buttonId);
    const table = document.getElementById(tableId);
    const chartContainer = document.getElementById(chartContainerId);
    const textSpan = button?.querySelector('span');

    if (!button || !table || !chartContainer) {
      console.error(`Elemente fehlen: button=${buttonId}, table=${tableId}, chart=${chartContainerId}`);
      return;
    }

    // Initiale Sichtbarkeit: Tabelle sichtbar, Chart unsichtbar
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

  // Toggle-Initialisierung
  toggleView('toggleCheapestPeriods', 'cheapestPeriodsTable', 'cheapestPeriodsChartContainer', 'cheapestPeriodsChart', 'bar', cheapestPeriods);
  toggleView('toggleMarketPrices', 'marketPricesTable', 'marketPricesChartContainer', 'marketPricesChart', 'line', marketPrices);
  toggleView('toggleChargingSchedule', 'chargingScheduleTable', 'chargingScheduleChartContainer', 'chargingScheduleChart', 'bar', scheduledChargingPeriods);

  // Status abrufen und aktualisieren
  fetchCurrentStatus();
  setInterval(fetchCurrentStatus, 10000);
  setInterval(() => window.location.reload(), 65000);
});

// Status abrufen
function fetchCurrentStatus() {
  fetch('/current-status', { method: 'GET' })
    .then(response => response.json())
    .then(data => {
      updateButtonState('modeBtn', data.currentMode === 'idle', 'fa-pause', 'fa-play', translations.idle, translations.automatic);
      updateButtonState('nightChargingBtn', data.nightChargingIdle, 'fa-moon', 'fa-moon', translations.nightIdle, translations.nightIdle);
      updateButtonState('chargingBtn', data.isCharging, 'fa-stop', 'fa-bolt', translations.charging, translations.stopped);

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

// Button-Status aktualisieren
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

// Aktion ausführen
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

// Ladeanzeige anzeigen/verbergen
function showLoader() {
  const loader = document.getElementById('loader');
  const progressText = document.getElementById('progress-text');
  if (loader) loader.style.display = 'block';
  if (progressText) progressText.style.display = 'block';
}

function hideLoader() {
  const loader = document.getElementById('loader');
  const progressText = document.getElementById('progress-text');
  if (loader) loader.style.display = 'none';
  if (progressText) progressText.style.display = 'none';
}

// Debounce-Funktion
function debounce(func, wait) {
  let timeout;
  return function (...args) {
    clearTimeout(timeout);
    timeout = setTimeout(() => func.apply(this, args), wait);
  };
}

// Debounced Funktionen
const debouncedToggleMode = debounce(() => {
  const btn = document.getElementById('modeBtn');
  handleAction('/toggle-mode', 'POST', { mode: btn.classList.contains('on') ? 'standard' : 'idle' });
}, 300);

const debouncedToggleNightCharging = debounce(() => {
  const btn = document.getElementById('nightChargingBtn');
  handleAction('/toggle-night-charging', 'POST', { nightChargingIdle: !btn.classList.contains('on') });
}, 300);

const debouncedToggleCharging = debounce(() => {
  const btn = document.getElementById('chargingBtn');
  if (!btn.classList.contains('on')) {
    confirmStartCharging();
  } else {
    handleAction('/toggle-charging', 'POST', { charging: 'stop' });
  }
}, 300);

// Ladebestätigung
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

// Tab anzeigen
function showTab(tabId) {
  document.querySelectorAll('.tab-pane').forEach(tab => {
    tab.classList.remove('show', 'active');
  });
  const tab = document.getElementById(tabId);
  if (tab) tab.classList.add('show', 'active');
}