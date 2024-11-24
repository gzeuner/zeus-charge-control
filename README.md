
# Zeus Charge Control

Zeus Charge Control - Eine Java-Anwendung zur Verwaltung von Ladeplänen für PV-Batteriespeicher basierend auf Marktpreisen. Diese Anwendung unterstützt die Sonnen API v2 für das Batteriemanagement.

## Open-Meteo Integration

Zeus Charge Control unterstützt verschiedene Marktpreis- und Wetterdaten-APIs. Wenn die Wetter-API von [Open-Meteo](https://open-meteo.com/) verwendet wird, ist zu beachten, dass diese nur für nicht-kommerzielle Zwecke kostenfrei ist. Für kommerzielle Nutzung ist ein API-Schlüssel erforderlich. Weitere Informationen findest du in den [Nutzungsbedingungen von Open-Meteo](https://open-meteo.com/en/terms).

## Funktionen

- **Batteriestatus**: Überwache den aktuellen Status deiner Batterie.
- **Marktpreismanagement**: Optimierte Ladeplanung basierend auf den besten verfügbaren Marktpreisen.
- **Preisdiagramm**: Visualisiere Marktpreise über die Zeit hinweg.
- **Wetterintegration**: Ladeentscheidungen basierend auf Wetterbedingungen, wie z.B. Bewölkungsgrad.

## Screenshots

Hier sind einige Screenshots der Anwendung in Aktion:

**Batteriestatus**

<img src="images/battery_status.jpg" alt="images/battery_status.jpg" width="60%">

**Bester Preis im Zeitrahmen**

<img src="images/best_price_in_scope.jpg" alt="best_price_in_scope.jpg" width="60%">

**Preisdiagramm**

<img src="images/price_chart.jpg" alt="price_chart.jpg" width="60%">

## Neue und verwendete Umgebungsvariablen

### Datenquelle
- **spring.datasource.url**: URL der Datenquelle (Standard: `jdbc:h2:mem:testdb`).
- **spring.datasource.driverClassName**: Treiberklasse für die Datenquelle (Standard: `org.h2.Driver`).
- **spring.datasource.username**: Benutzername für die Datenbank (Standard: `sa`).
- **spring.datasource.password**: Passwort für die Datenbank (Standard: `password`).
- **spring.jpa.database-platform**: Hibernate-Dialect (Standard: `org.hibernate.dialect.H2Dialect`).
- **spring.h2.console.enabled**: Aktiviert die H2-Webkonsole (Standard: `true`).
- **spring.h2.console.path**: Pfad zur H2-Konsole (Standard: `/h2-console`).

### Ladeeinstellungen
- **battery.target.stateOfCharge**: Ziel-Ladezustand der Batterie in Prozent (Standard: `90`).
- **battery.chargingPoint**: Ladepunkt der Batterie in Watt (Standard: `4500`).
- **battery.large.consumer.threshold**: Schwellwert für große Verbraucheraktivität (Standard: `0.5`).
- **battery.status.cache.duration.seconds**: Dauer für den Status-Cache in Sekunden (Standard: `60`).

### Marktpreise
- **marketdata.source**: Datenquelle für Marktpreise (`awattar` oder `tibber`).
- **marketdata.max.acceptable.price.cents**: Maximal akzeptabler Marktpreis in Cent (Standard: `15`).

### Logging
- **logging.file.name**: Speicherort für Logdateien (Standard: `logs/power.log`).
- **logging.file.max-size**: Maximale Logdateigröße (Standard: `10MB`).

### Nachtperioden
- **night.start**, **night.end**: Definiert Start- und Endzeit der Nachtperiode (Standard: `21` bzw. `6`).

### Wetterdaten
- **weather.api.cloudcover.threshold**: Schwellwert für Bewölkungsgrad (Standard: `60`).

## Lizenz

Diese Software wird unter der Apache License, Version 2.0 <a href="http://www.apache.org/licenses/LICENSE-2.0">(http://www.apache.org/licenses/LICENSE-2.0)</a> bereitgestellt.

Sie enthält Software, die vom Spring Boot-Projekt entwickelt wurde <a href="http://spring.io/projects/spring-boot">(http://spring.io/projects/spring-boot)</a>.
Spring Boot-Komponenten sind unter der Apache License, Version 2.0 lizenziert <a href="http://www.apache.org/licenses/LICENSE-2.0">(http://www.apache.org/licenses/LICENSE-2.0)</a>.

Details zur Lizenz findest du in `LICENSE.txt`, `license_de.html` oder `license_en.html`.

---

# Zeus Charge Control

Zeus Charge Control - A Java application for managing battery charging schedules based on market prices. This application supports the Sonnen API v2 for battery management.

## Open-Meteo Integration

Zeus Charge Control supports various market price and weather data APIs. When using the weather API from [Open-Meteo](https://open-meteo.com/), please note that it is free of charge only for non-commercial purposes. For commercial use, an API key is required. More information can be found in the [Open-Meteo terms of use](https://open-meteo.com/en/terms).

## Features

- **Battery Status**: Monitor the current status of your battery.
- **Market Price Management**: Optimized charging scheduling based on the best available market prices.
- **Price Chart**: Visualize market prices over time.
- **Weather Integration**: Charging decisions based on weather conditions, such as cloud cover.

## Screenshots

Here are some screenshots of the application in action:

**Battery Status**

<img src="images/battery_status.jpg" alt="images/battery_status.jpg" width="60%">

**Best Price in Scope**

<img src="images/best_price_in_scope.jpg" alt="best_price_in_scope.jpg" width="60%">

**Price Chart**

<img src="images/price_chart.jpg" alt="price_chart.jpg" width="60%">

## Updated and Used Environment Variables

### Datasource
- **spring.datasource.url**: URL of the datasource (Default: `jdbc:h2:mem:testdb`).
- **spring.datasource.driverClassName**: Driver class for the datasource (Default: `org.h2.Driver`).
- **spring.datasource.username**: Username for the database (Default: `sa`).
- **spring.datasource.password**: Password for the database (Default: `password`).
- **spring.jpa.database-platform**: Hibernate dialect (Default: `org.hibernate.dialect.H2Dialect`).
- **spring.h2.console.enabled**: Enables the H2 web console (Default: `true`).
- **spring.h2.console.path**: Path to the H2 console (Default: `/h2-console`).

### Charging Settings
- **battery.target.stateOfCharge**: Target state of charge of the battery in percent (Default: `90`).
- **battery.chargingPoint**: Charging point of the battery in watts (Default: `4500`).
- **battery.large.consumer.threshold**: Threshold for large consumer activity (Default: `0.5`).
- **battery.status.cache.duration.seconds**: Duration of the status cache in seconds (Default: `60`).

### Market Prices
- **marketdata.source**: Data source for market prices (`awattar` or `tibber`).
- **marketdata.max.acceptable.price.cents**: Maximum acceptable market price in cents (Default: `15`).

### Logging
- **logging.file.name**: File path for logs (Default: `logs/power.log`).
- **logging.file.max-size**: Maximum log file size (Default: `10MB`).

### Night Periods
- **night.start**, **night.end**: Defines start and end time of the night period (Default: `21` and `6`).

### Weather Data
- **weather.api.cloudcover.threshold**: Threshold for cloud cover (Default: `60`).

## License

This software is provided under the Apache License, Version 2.0 <a href="http://www.apache.org/licenses/LICENSE-2.0">(http://www.apache.org/licenses/LICENSE-2.0)</a>.

It includes software developed by the Spring Boot project <a href="http://spring.io/projects/spring-boot">(http://spring.io/projects/spring-boot)</a>.
Spring Boot components are licensed under the Apache License, Version 2.0 <a href="http://www.apache.org/licenses/LICENSE-2.0">(http://www.apache.org/licenses/LICENSE-2.0)</a>.

For license details, see `LICENSE.txt`, `license_de.html`, or `license_en.html`.
