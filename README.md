# Zeus Charge Control / Zeus Ladeoptimierung

Zeus Charge Control ist eine **Java / Spring Boot** Anwendung zur preis-, zustands- und optional wetterbasierten Steuerung eines PV-Batteriespeichers über die **Sonnen API v2**.

Die Anwendung:

- lädt Marktpreise (aWATTar oder Tibber mit bidirektionaler Fallback-Logik)
- plant Ladefenster dynamisch anhand RSOC, Preisparametern und optionaler Wetterprognose
- optimiert Ladepläne kontinuierlich (Event-basiert + zeitgesteuert)
- bietet eine Web-UI zur manuellen Steuerung
- bleibt bei API-Ausfällen robust und protokolliert Fehler

---

## ⚠️ Status: Experimental / Private Use

Dieses Projekt befindet sich in einem **experimentellen Entwicklungsstadium** und ist primär für:

- private Nutzung
- Tests
- Lern- und Evaluationszwecke

gedacht.

Es ist **nicht für den produktiven Dauerbetrieb in sicherheitskritischen oder kommerziellen Umgebungen vorgesehen**.

Automatisierte Ladeentscheidungen erfolgen daten- und konfigurationsbasiert und sollten vor produktivem Einsatz sorgfältig geprüft werden.

---

## 📦 Distribution & Build

Dieses Projekt wird **bewusst ohne vorkompilierte Binaries oder Releases** bereitgestellt.

Die Anwendung ist ausschließlich als **Quellcode** verfügbar und muss lokal gebaut werden:

```bash
mvn clean package
mvn test
mvn spring-boot:run
```

Alternativ nach dem Build:

```bash
java -jar target/zeus-power-control-3.0-RELEASE.jar
```

Die Verantwortung für Build, Konfiguration und Betrieb liegt vollständig beim Nutzer.

---

## Technologie-Stack

### Backend

- Java 17
- Spring Boot 2.7.18
- Spring Web
- Spring Scheduling
- Spring Retry
- Spring Data JPA
- H2 (In-Memory, Standard)

### Frontend

- Bootstrap 5
- Chart.js
- jQuery
- Luxon
- Thymeleaf

### Projektkoordinaten

```
groupId:    de.zeus.power
artifactId: zeus-power-control
version:    3.0-RELEASE
```

---

## Kernfunktionen

### Dynamische Ladeplanung

- Nacht- und Tagesfenster
- Auswahl günstigster Perioden im konfigurierbaren Toleranzbereich
- Maximalperioden im Nachtfenster
- Catch-up-Start innerhalb aktiver Fenster bei RSOC-Abfall
- Automatische Entfernung von Ladeplänen bei Ziel-RSOC-Erreichung

### Laufende Re-Optimierung

- Bei `MarketPricesUpdatedEvent`
- Stündlich per Scheduler
- Bei RSOC-/Modusänderungen

### Modi

- Standard (automatisch)
- Idle
- Night-Idle
- Manuelles Start/Stop
- Rückgabe an Energy Manager

### Preislogik

- Netto- und Bruttoanzeige
- Konfigurierbare Zuschläge (YAML)
- Flexibilitätsschwelle (`marketdata.price.flexibility.threshold`)
- Maximal akzeptabler Preis

### Wetter-Integration (optional)

- Open-Meteo API
- Wolkendeckungs-Schwellenwert (`weather.sunny-threshold`)
- Optionales Deferring bei hoher Sonnenerwartung

### Fehlertoleranz

- Retry-Mechanismen
- Logging bei Batterie-/API-Ausfällen
- App bleibt lauffähig bei temporärer Nichterreichbarkeit

---

## Scheduler-Verhalten

### Preisupdate + Persistenz

- Beim Start (`ApplicationReadyEvent`)
- Per Cron (`scheduled.job.cron`, Default: `0 15 14,22,2 * * *`)

### Optimierung

- Bei Preisupdate-Event
- Stündlich (`0 0 * * * ?`)

### RSOC-/Modusüberwachung

- Fixed Rate (`battery.automatic.mode.check.interval`, Default 15000 ms)

---

## API-Integrationen

### Batterie
- Sonnen API v2 (`/status`, `/setpoint/...`)

### Marktpreise
- aWATTar
- Tibber
- Bidirektionale Fallback-Logik

### Wetter
- Open-Meteo (optional)

---

## Verfügbare HTTP-Endpunkte

### UI

- `GET /charging-status`
- `GET /license?lang=de|en`

### Status

- `GET /current-status`

### Steuerung

- `POST /toggle-mode` (`idle|standard`)
- `POST /toggle-charging` (`start|stop`)
- `POST /start-charging`
- `POST /reset-automatic?force=true|false`
- `POST /reset-idle`
- `POST /toggle-night-charging`
- `POST /night-charging-window`

---

## Beispielkonfiguration

### Minimal Batterie

```properties
battery.url=http://<sonnen-ip>/api/v2
battery.authToken=<token>
```

Ohne diese Werte startet die App, Batteriesteuerung ist jedoch deaktiviert.

---

### Wichtige Properties (Auszug)

```properties
server.port=8080

marketdata.source=awattar
marketdata.acceptable.price.cents=15
marketdata.max.acceptable.price.cents=15
marketdata.price.flexibility.threshold=2

battery.target.stateOfCharge=90
battery.inverter.max.watts=4600
battery.grid.import.limit.watts=4600
battery.pv.only.enabled=true

night.start=19
night.end=6
battery.nightChargingIdle=false

weather.api.enabled=true
weather.api.latitude=52.52
weather.api.longitude=13.405
weather.sunny-threshold=40

battery.max.capacity.wh=10000
BATTERY_MAX_CAPACITY=10000
```

---

## Lokale Datenhaltung

Standard:

```properties
spring.datasource.url=jdbc:h2:mem:testdb
```

Optional mit H2-Konsole (`/h2-console`).

---

## Tests

Vorhandene Unit-Tests u. a.:

- `ChargingUtilsTest`
- `ChargingManagementServiceTest`
- `BatteryManagementServiceTest`
- `PriceDisplayServiceTest`

`mvn test` läuft erfolgreich (Stand aktueller Code).

---

## Disclaimer & Haftungsausschluss

### Keine Verbindung zur Sonnen GmbH

Dieses Projekt steht in keiner Verbindung zur Sonnen GmbH.  
Die Software wurde nicht von der Sonnen GmbH entwickelt, bereitgestellt oder unterstützt.

### Haftungsausschluss

Die Nutzung erfolgt auf eigenes Risiko.  
Die Software wird ohne jegliche Gewährleistung bereitgestellt.  

Eine Haftung für Schäden, die aus der Nutzung der Software entstehen, ist – soweit gesetzlich zulässig – ausgeschlossen.  
Insbesondere wird keine Gewähr für die Richtigkeit von Marktdaten, die Verfügbarkeit externer APIs oder die Eignung für einen bestimmten Zweck übernommen.

---

## Lizenz

Apache License 2.0  
(siehe `LICENSE`)