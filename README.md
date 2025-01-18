# Zeus Charge Control

**Zeus Charge Control** ist eine leistungsstarke Java-Anwendung, die Ladepläne für PV-Batteriespeicher auf Basis dynamischer Marktpreise verwaltet. Durch die Integration von Wetter- und Preisdaten optimiert sie Ladezeiten für maximale Effizienz. Unterstützt werden die **Sonnen API v2** und die Wetter-API von **Open-Meteo**.

---

## Hauptfunktionen

- **Batteriestatus-Überwachung**: Echtzeit-Überblick über den Ladezustand.
- **Marktpreismanagement**: Automatische Anpassung der Ladezeiten an Marktpreise mit konfigurierbaren Schwellenwerten.
- **Dynamische Nachtplanung**: Optimierung der drei günstigsten Ladezeiten mit flexiblen Zusatzoptionen.
- **Preisdiagramme**: Visualisierung von Marktpreisen zur Entscheidungsunterstützung.
- **Wetterintegration**: Ladeentscheidungen basierend auf Wetterdaten (z. B. Bewölkungsgrad).

---

## Dynamische Ladeplanung

Zeus Charge Control kombiniert mehrere Faktoren für eine präzise Ladeplanung:

1. **Dynamische Preisschwellen**: Basierend auf Minimum, Maximum und Median der Marktpreise.
2. **Flexibilitätstoleranz**: Zusätzliche Perioden knapp oberhalb des Schwellenwerts können berücksichtigt werden.
3. **Optimierung der Nachtplanung**: Auswahl der günstigsten Ladeperioden während der Nacht, priorisiert nach Marktpreisen.
4. **Tageszeit-Pufferung**: Validierung und Zwischenspeicherung günstiger Ladeperioden außerhalb der Nachtzeit.
5. **RSOC-Überwachung**: Dynamische Anpassung der Ladeplanung basierend auf dem Ladezustand (RSOC - Relative State of Charge).
6. **Individuelle Konfiguration**: Alle Parameter lassen sich flexibel über die `application.properties` anpassen.

### Beispiel:
Mit Standardeinstellungen werden die **drei günstigsten Ladeperioden** priorisiert. Falls nicht genügend günstige Zeiträume verfügbar sind, erweitert die Toleranzschwelle die Optionen. Während der Nacht werden nur Perioden innerhalb des definierten Zeitfensters berücksichtigt, mit einer Rückkehr zum Automatikmodus nach Ende der Nacht.

---

## Integration von APIs

- **Marktpreise**: Unterstützt werden die Quellen `awattar` und `tibber`.
- **Wetterdaten**: Die [Open-Meteo API](https://open-meteo.com/) liefert Wettervorhersagen und ist für nicht-kommerzielle Zwecke kostenlos nutzbar.

> **Hinweis**: Für kommerzielle Anwendungen ist ein API-Schlüssel erforderlich. Details finden sich in den [Nutzungsbedingungen von Open-Meteo](https://open-meteo.com/en/terms).

---

## Screenshots

### Batteriestatus
![Batteriestatus](images/battery_status.jpg)

### Marktpreis-Visualisierung
![Preisdiagramm](images/price_chart.jpg)

---

## Konfigurationsparameter

### Datenbank
- **`spring.datasource.url`**: URL der Datenbank (Standard: `jdbc:h2:mem:testdb`)
- **`spring.datasource.username`**: Benutzername (Standard: `sa`)
- **`spring.datasource.password`**: Passwort (Standard: `password`)

### Ladeeinstellungen
- **`battery.target.stateOfCharge`**: Ziel-Ladezustand in Prozent (Standard: `90`)
- **`battery.chargingPoint`**: Ladepunkt in Watt (Standard: `4500`)
- **`battery.large.consumer.threshold`**: Schwellenwert für große Verbraucher (Standard: `0.5`)

### Marktpreise
- **`marketdata.source`**: Quelle (`awattar`, `tibber`)
- **`marketdata.max.acceptable.price.cents`**: Maximale akzeptable Preise in Cent (Standard: `15`)
- **`marketdata.price.flexibility.threshold`**: Toleranzschwelle (Standard: `2`)

### Nachtplanung
- **`night.start`**: Start der Nachtzeit (Standard: `22`)
- **`night.end`**: Ende der Nachtzeit (Standard: `6`)

---

## Lizenz

Zeus Charge Control wird unter der **Apache License, Version 2.0** bereitgestellt. Weitere Details findest Du in der Datei `LICENSE.txt`.

> **Disclaimer**: Diese Software wird ohne Garantie bereitgestellt. Support oder Fehlerfreiheit sind nicht gewährleistet.

---

# Zeus Charge Control (English)

**Zeus Charge Control** is a powerful Java application designed to manage charging schedules for PV battery storage systems based on dynamic market prices. By integrating weather and price data, it optimizes charging times for maximum efficiency. The application supports **Sonnen API v2** and the weather API from **Open-Meteo**.

---

## Key Features

- **Battery Status Monitoring**: Real-time overview of the battery's charge level.
- **Market Price Management**: Automatic adjustment of charging times to market prices with configurable thresholds.
- **Dynamic Night Planning**: Optimization of the three cheapest charging periods with flexible additional options.
- **Price Charts**: Visualization of market prices to support decision-making.
- **Weather Integration**: Charging decisions based on weather data (e.g., cloud cover).

---

## Dynamic Charging Planning

Zeus Charge Control combines multiple factors for precise charging planning:

1. **Dynamic Price Thresholds**: Based on the minimum, maximum, and median market prices.
2. **Flexibility Tolerance**: Additional periods slightly above the threshold can be considered.
3. **Nighttime Optimization**: Selection of the cheapest charging periods during the night, prioritized by market prices.
4. **Daytime Buffering**: Validation and caching of favorable charging periods outside nighttime.
5. **RSOC Monitoring**: Dynamic adjustment of charging schedules based on the Relative State of Charge (RSOC).
6. **Custom Configuration**: All parameters can be flexibly adjusted via `application.properties`.

### Example:
With default settings, the **three cheapest charging periods** are prioritized. If insufficient cheap periods are available, the tolerance threshold expands the options. During the night, only periods within the defined timeframe are considered, with a return to automatic mode after the night ends.

---

## API Integration

- **Market Prices**: Supported sources are `awattar` and `tibber`.
- **Weather Data**: The [Open-Meteo API](https://open-meteo.com/) provides weather forecasts and is free for non-commercial use.

> **Note**: For commercial applications, an API key is required. See the [Open-Meteo Terms of Use](https://open-meteo.com/en/terms) for details.

---

## Screenshots

### Battery Status
![Battery Status](images/battery_status.jpg)

### Market Price Visualization
![Price Chart](images/price_chart.jpg)

---

## Configuration Parameters

### Database
- **`spring.datasource.url`**: Database URL (default: `jdbc:h2:mem:testdb`)
- **`spring.datasource.username`**: Username (default: `sa`)
- **`spring.datasource.password`**: Password (default: `password`)

### Charging Settings
- **`battery.target.stateOfCharge`**: Target state of charge in percent (default: `90`)
- **`battery.chargingPoint`**: Charging point in watts (default: `4500`)
- **`battery.large.consumer.threshold`**: Threshold for large consumers (default: `0.5`)

### Market Prices
- **`marketdata.source`**: Source (`awattar`, `tibber`)
- **`marketdata.max.acceptable.price.cents`**: Maximum acceptable prices in cents (default: `15`)
- **`marketdata.price.flexibility.threshold`**: Tolerance threshold (default: `2`)

### Night Planning
- **`night.start`**: Start of nighttime (default: `22`)
- **`night.end`**: End of nighttime (default: `6`)

---

## License

Zeus Charge Control is provided under the **Apache License, Version 2.0**. For more details, see the `LICENSE.txt` file.

> **Disclaimer**: This software is provided "as is" without any warranties. Support or error-free functionality is not guaranteed.
