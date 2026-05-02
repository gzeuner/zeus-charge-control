# ⚡ Zeus Charge Control

![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.11-brightgreen)
![Maven](https://img.shields.io/badge/Maven-Build-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)
![Status](https://img.shields.io/badge/Status-Experimental-orange)
![Last Commit](https://img.shields.io/github/last-commit/gzeuner/zeus-charge-control)

**Intelligente Ladeplanung für PV-Batteriespeicher – automatisch zum besten Preis.**

---

![Zeus Charge Control Showcase](./images/zeus-charge-control-showcase.png)

> 💡 Dynamische Strompreise, automatische Ladeplanung und moderne UI – alles in einer Anwendung.

---

> ⚠️ **Hinweis:**  
> Unterstützt aktuell ausschließlich **PV-Batteriespeicher mit Sonnen API v2**

---

## 🧠 Was ist Zeus Charge Control?

**Zeus Charge Control** ist eine **Java / Spring Boot** Anwendung zur intelligenten Steuerung eines PV-Batteriespeichers basierend auf:

- aktuellen Strompreisen  
- Batteriezustand (RSOC = aktueller Ladezustand in %)  
- optionalen Wetterdaten  

Die Anwendung:

- 📊 lädt Marktpreise (aWATTar / Tibber mit Fallback)
- ⚡ plant automatisch optimale Ladezeitpunkte
- 🔁 optimiert kontinuierlich (Event + Scheduler)
- 🖥️ stellt eine moderne Web-UI bereit
- 🛡️ bleibt stabil bei API-Ausfällen

---

## ⚠️ Status

👉 Produktiver Einsatz ist möglich.  
Die Nutzung erfolgt eigenverantwortlich.  
Konfiguration, Betrieb und Bewertung der automatisierten Ladeentscheidungen liegen beim jeweiligen Betreiber der Installation.

---

## 🚀 Features

- ⚡ Dynamische Ladeplanung
- 📉 Optimierung nach Marktpreisen
- 🌤️ Optionale Wetterintegration
- 🎛️ Manuelle Steuerung via Web-UI
- 🎨 Mehrere moderne Themes
- 🔄 Event- & zeitgesteuerte Re-Optimierung
- 🛡️ Fehlertolerant & robust

---

## 🔧 Batterie-Konfiguration (wichtig!)

Die Anwendung muss an deine reale Batterie angepasst werden:

```properties
battery.url=${BATTERY_URL:}
battery.authToken=${BATTERY_AUTH_TOKEN:}

battery.inverter.max.watts=${BATTERY_INVERTER_MAX_WATTS:4600}
battery.max.capacity.wh=${BATTERY_MAX_CAPACITY_WH:10000}
```

👉 Diese Werte müssen korrekt gesetzt sein, sonst arbeitet Zeus nicht zuverlässig.

---

## 📦 Build & Start

```bash
mvn clean verify
mvn spring-boot:run
```

oder:

```bash
java -jar target/zeus-power-control-3.0-RELEASE.jar
```

### Voraussetzungen

- Java 21  
- Maven 3.9+

---

## ⚙️ Kernlogik

### 🔋 Dynamische Ladeplanung

- Auswahl der günstigsten Zeiträume
- Steuerung basierend auf RSOC
- automatische Beendigung von Ladevorgängen,
  sobald der Ziel-Ladezustand erreicht ist

---

### 🔁 Re-Optimierung

- bei Preisupdates
- stündlich per Scheduler
- bei Zustandsänderungen

---

## 🔌 Unterstützte Systeme

- ✅ Sonnen API v2 kompatible PV-Speicher

---

## 📄 Lizenz

Apache License 2.0
