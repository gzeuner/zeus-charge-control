# Zeus Charge Control

Zeus Charge Control - Eine Java-Anwendung zur Verwaltung von Ladeplänen für PV-Batteriespeicher basierend auf Marktpreisen. Die Anwendung nutzt dynamische Algorithmen zur Auswahl der optimalen Ladeperioden unter Berücksichtigung von Wetter- und Preisfaktoren. Sie unterstützt sowohl die Sonnen API v2 als auch Open-Meteo für Datenintegration.

## Open-Meteo Integration

Zeus Charge Control integriert verschiedene Marktpreis- und Wetterdaten-APIs. Die Wetter-API von [Open-Meteo](https://open-meteo.com/) ist für nicht-kommerzielle Zwecke kostenlos nutzbar. Für kommerzielle Anwendungen ist ein API-Schlüssel erforderlich. Weitere Informationen finden sich in den [Nutzungsbedingungen von Open-Meteo](https://open-meteo.com/en/terms).

## Funktionen

- **Batteriestatus**: Überwachung des aktuellen Ladezustands der Batterie.
- **Marktpreismanagement**: Dynamische Ladeplanung basierend auf Marktpreisen, inklusive flexibler Schwellenwerte.
- **Dynamische Nachtplanung**: Priorisiert die drei günstigsten Ladeperioden mit zusätzlichen optionalen Perioden.
- **Preisdiagramm**: Visualisierung von Marktpreisen.
- **Wetterintegration**: Ladeentscheidungen basierend auf Wetterbedingungen wie Bewölkungsgrad.

## Dynamische Ladeplanung

Die dynamische Ladeplanung priorisiert die günstigsten Ladeperioden und integriert zusätzliche Toleranzmechanismen. Dies geschieht durch die Kombination folgender Faktoren:

1. **Dynamische Preisschwellen**: Basierend auf Minimum-, Maximum- und Median-Preisen wird ein Schwellenwert berechnet, der die günstigsten Perioden identifiziert.
2. **Flexibilitätstoleranz**: Zusätzliche Perioden, die knapp über dem Schwellenwert liegen, können optional berücksichtigt werden.
3. **Konfigurierbare Parameter**: Über die `application.properties` können folgende Einstellungen angepasst werden:
    - **marketdata.max.acceptable.price.cents**: Maximale Preisgrenze in Cent pro kWh.
    - **marketdata.price.flexibility.threshold**: Toleranzschwelle für zusätzliche Perioden.
    - **night.start** und **night.end**: Definieren den Zeitraum der Nachtplanung.
    - **battery.target.stateOfCharge**: Ziel-Ladezustand der Batterie.

### Beispiel:

Mit den Standardeinstellungen werden die drei günstigsten Perioden priorisiert. Falls keine ausreichenden günstigen Perioden verfügbar sind, wird die Flexibilitätstoleranz genutzt, um zusätzliche Perioden einzubeziehen.

Zeus Charge Control steht in keinerlei Verbindung zur Firma Sonnen, Tibber oder Awattar. Es gibt keinen Anspruch auf Fehlerfreiheit, Upgrades oder Support. Der Quellcode der Software ist kostenlos verfügbar.

## Screenshots

Hier einige Beispiele:

**Batteriestatus**

<img src="images/battery_status.jpg" alt="Batteriestatus" width="60%">

**Bester Preis im Zeitrahmen**

<img src="images/best_price_in_scope.jpg" alt="Bester Preis" width="60%">

**Preisdiagramm**

<img src="images/price_chart.jpg" alt="Preisdiagramm" width="60%">

## Umgebungsvariablen

### Datenquelle
- **spring.datasource.url**: URL der Datenbank (Standard: `jdbc:h2:mem:testdb`).
- **spring.datasource.driverClassName**: Treiberklasse (Standard: `org.h2.Driver`).
- **spring.datasource.username**: Benutzername (Standard: `sa`).
- **spring.datasource.password**: Passwort (Standard: `password`).
- **spring.jpa.database-platform**: Hibernate-Dialect (Standard: `org.hibernate.dialect.H2Dialect`).
- **spring.h2.console.enabled**: Aktiviert H2-Konsole (Standard: `true`).

### Ladeeinstellungen
- **battery.target.stateOfCharge**: Ziel-Ladezustand in Prozent (Standard: `90`).
- **battery.chargingPoint**: Ladepunkt in Watt (Standard: `4500`).
- **battery.large.consumer.threshold**: Schwellenwert für große Verbraucher (Standard: `0.5`).

### Marktpreise
- **marketdata.source**: Quelle für Marktpreise (`awattar` oder `tibber`).
- **marketdata.max.acceptable.price.cents**: Maximal akzeptabler Marktpreis in Cent (Standard: `15`).
- **marketdata.price.flexibility.threshold**: Dynamische Preistoleranz (Standard: `2`).

### Nachtplanung
- **night.start**: Beginn der Nachtperiode (Standard: `21`).
- **night.end**: Ende der Nachtperiode (Standard: `6`).

## Lizenz

Diese Software wird unter der Apache License, Version 2.0 bereitgestellt. Details siehe `LICENSE.txt`.

---

# Zeus Charge Control

Zeus Charge Control - A Java application for managing battery charging schedules based on market prices. It uses dynamic algorithms to optimize charging periods, considering weather and price factors. The application supports the Sonnen API v2 and Open-Meteo for data integration.

## Open-Meteo Integration

Zeus Charge Control integrates various market price and weather data APIs. The weather API from [Open-Meteo](https://open-meteo.com/) is free for non-commercial purposes. For commercial applications, an API key is required. More information is available in the [Open-Meteo terms of use](https://open-meteo.com/en/terms).

## Features

- **Battery Status**: Monitor the current state of your battery.
- **Market Price Management**: Dynamic charging schedules based on market prices, including flexible thresholds.
- **Dynamic Nighttime Optimization**: Prioritizes the three cheapest periods with additional optional periods.
- **Price Chart**: Visualize market prices.
- **Weather Integration**: Charging decisions based on weather conditions, such as cloud cover.

## Dynamic Charging Optimization

Dynamic charging optimization prioritizes the cheapest charging periods while incorporating tolerance mechanisms. It combines the following factors:

1. **Dynamic Price Thresholds**: Minimum, maximum, and median prices are used to calculate a threshold that identifies the cheapest periods.
2. **Flexibility Tolerance**: Additional periods slightly above the threshold can optionally be included.
3. **Configurable Parameters**: The following settings in `application.properties` can be adjusted:
    - **marketdata.max.acceptable.price.cents**: Maximum price limit in cents per kWh.
    - **marketdata.price.flexibility.threshold**: Tolerance threshold for additional periods.
    - **night.start** and **night.end**: Define the nighttime planning window.
    - **battery.target.stateOfCharge**: Target state of charge for the battery.

### Example:

With default settings, the three cheapest periods are prioritized. If insufficient cheap periods are available, the flexibility tolerance is applied to include additional periods.

Zeus Charge Control is not affiliated with Sonnen, Tibber, or Awattar. There is no claim for error-free functionality, upgrades, or support. The source code of the software is available free of charge.

## Screenshots

Examples in action:

**Battery Status**

<img src="images/battery_status.jpg" alt="Battery Status" width="60%">

**Best Price in Scope**

<img src="images/best_price_in_scope.jpg" alt="Best Price" width="60%">

**Price Chart**

<img src="images/price_chart.jpg" alt="Price Chart" width="60%">

## Environment Variables

### Datasource
- **spring.datasource.url**: URL of the database (Default: `jdbc:h2:mem:testdb`).
- **spring.datasource.driverClassName**: Driver class (Default: `org.h2.Driver`).
- **spring.datasource.username**: Username (Default: `sa`).
- **spring.datasource.password**: Password (Default: `password`).
- **spring.jpa.database-platform**: Hibernate dialect (Default: `org.hibernate.dialect.H2Dialect`).
- **spring.h2.console.enabled**: Enables H2 console (Default: `true`).

### Charging Settings
- **battery.target.stateOfCharge**: Target state of charge in percent (Default: `90`).
- **battery.chargingPoint**: Charging point in watts (Default: `4500`).
- **battery.large.consumer.threshold**: Threshold for large consumer activity (Default: `0.5`).

### Market Prices
- **marketdata.source**: Data source for market prices (`awattar` or `tibber`).
- **marketdata.max.acceptable.price.cents**: Maximum acceptable market price in cents (Default: `15`).
- **marketdata.price.flexibility.threshold**: Dynamic price tolerance (Default: `2`).

### Nighttime Planning
- **night.start**: Start of the night period (Default: `21`).
- **night.end**: End of the night period (Default: `6`).

## License

This software is provided under the Apache License, Version 2.0. Details can be found in `LICENSE.txt`. 
