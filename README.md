# ⚡ Zeus Charge Control

![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen)
![Version](https://img.shields.io/badge/Version-4.0.0-teal)
![Maven](https://img.shields.io/badge/Maven-Build-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)
![Status](https://img.shields.io/badge/Status-Experimental-orange)
![Last Commit](https://img.shields.io/github/last-commit/gzeuner/zeus-charge-control)

**Intelligente Ladeplanung für PV-Batteriespeicher – automatisch zu günstigen Strompreis-Zeiten.**

**Smart charging control for PV battery storage systems – automatically using low-price electricity windows.**

---

![Zeus Charge Control 4.0 — neue mobile GUI in Solar, Forest, Midnight und Nord](./images/zeus-charge-control-showcase.png)

*Produktvisualisierung auf Basis echter GUI-Aufnahmen mit Beispieldaten. / Product visualization based on actual GUI captures with sample data.*

**4.0.0 · Neues Energie-Dashboard · Fünf Themes · Live-Status · Desktop & Mobil**

**4.0.0 · Redesigned energy dashboard · Five themes · Live status · Desktop & mobile**

[Screenshots aller Themes / All theme screenshots](./docs/screenshots.md) · [Abhängigkeiten / Dependencies](./docs/dependencies.md) · [Prüfbericht / Verification](./docs/verification.md)

> 💡 Dynamische Strompreise, automatische Ladeplanung und moderne Web-UI – gebündelt in einer Anwendung.  
> 💡 Dynamic electricity prices, automated charging logic and a modern web UI – bundled into one application.

---

# 🇩🇪 Deutsche Dokumentation

## ⚠️ Wichtiger Hinweis

**Zeus Charge Control unterstützt aktuell ausschließlich PV-Batteriespeicher mit Sonnen API v2.**

Andere Batteriespeicher, Wechselrichter oder Energiemanagementsysteme werden derzeit nicht unterstützt.

👉 **Produktiver Einsatz kann möglich sein, erfolgt aber immer eigenverantwortlich.**  
Ob die Anwendung für die eigene Anlage geeignet ist, hängt von der konkreten Installation, der Konfiguration, den verfügbaren Schnittstellen und dem laufenden Monitoring durch den Betreiber ab.

Konfiguration, Monitoring und Bewertung der automatisierten Ladeentscheidungen liegen beim jeweiligen Betreiber der Installation.

---

## 🧠 Was ist Zeus Charge Control?

**Zeus Charge Control** ist eine **Java-/Spring-Boot-Anwendung** zur intelligenten Ladeplanung eines PV-Batteriespeichers.

Die Anwendung nutzt dynamische Strompreise, den aktuellen Batteriezustand und optional Wetterdaten, um günstige Ladezeitfenster zu erkennen und den Speicher entsprechend zu steuern.

Kurz gesagt:  
**Wenn Strom besonders günstig ist, kann Zeus Charge Control den Speicher gezielt laden – sofern deine Konfiguration und deine Anlage dazu passen.**

---

## 👥 Für wen ist dieses Projekt gedacht?

Zeus Charge Control richtet sich an technisch versierte Betreiber eines unterstützten PV-Batteriespeichers mit **Sonnen API v2**, die dynamische Strompreise nutzen und ihre Ladeplanung eigenverantwortlich automatisieren möchten.

Geeignet ist das Projekt insbesondere für Nutzerinnen und Nutzer, die:

- einen unterstützten Sonnen-PV-Batteriespeicher betreiben
- Zugriff auf die Sonnen API v2 ihrer Anlage haben
- dynamische Stromtarife oder Marktpreisdaten nutzen möchten
- technische Konfigurationen sicher einschätzen können
- bereit sind, den Betrieb der Anwendung aktiv zu überwachen

Nicht geeignet ist das Projekt für Personen, die eine universelle Plug-and-play-Lösung ohne technische Prüfung, Konfiguration oder Monitoring erwarten.

---

## 🔋 Unterstützte Grundlage

Aktuell technisch unterstützt:

- ✅ PV-Batteriespeicher mit **Sonnen API v2**

Derzeit nicht unterstützt:

- ❌ andere Batteriespeicher ohne Sonnen API v2
- ❌ universelle Wechselrichtersteuerung
- ❌ vollständige Energiemanagementsysteme
- ❌ sicherheitskritische oder netzrelevante Steuerungsfunktionen

---

## 🚫 Was Zeus Charge Control nicht macht

Zeus Charge Control ist kein vollständiges Energiemanagementsystem und ersetzt keine fachliche Prüfung der eigenen Anlage.

Die Anwendung:

- garantiert keine Stromkostenersparnis
- erkennt nicht automatisch jede technische Besonderheit deiner Installation
- ersetzt kein Monitoring durch den Betreiber
- ist keine offizielle Software der Sonnen GmbH
- ist nicht für sicherheitskritische, medizinische oder netzrelevante Steuerungsaufgaben gedacht
- ersetzt keine zertifizierte Steuerung oder fachkundige Bewertung

---

## 🚀 Features

- ⚡ Dynamische Ladeplanung anhand von Strompreisen
- 📉 Optimierung auf günstige Marktpreis-Zeiträume
- 🔋 Steuerung unter Berücksichtigung des Batteriezustands
- 🌤️ Optionale Wetterintegration
- 🎛️ Manuelle Steuerung über die Web-UI
- 🎨 Mehrere moderne UI-Themes
- 🔄 Event- und zeitgesteuerte Re-Optimierung
- 🛡️ Robustes Verhalten bei API- oder Datenproblemen

---

## 🔧 Batterie-Konfiguration

Die Anwendung muss an deine reale Batterie angepasst werden. Besonders wichtig sind die Adresse deiner Batterie, die Authentifizierung und die technischen Leistungsdaten.

```properties
battery.url=${BATTERY_URL:}
battery.authToken=${BATTERY_AUTH_TOKEN:}

battery.inverter.max.watts=${BATTERY_INVERTER_MAX_WATTS:4600}
battery.max.capacity.wh=${BATTERY_MAX_CAPACITY_WH:10000}
```

### Was bedeuten diese Werte?

| Einstellung | Bedeutung |
|---|---|
| `battery.url` | Adresse deiner Sonnen-Batterie im lokalen Netzwerk oder erreichbaren Netz |
| `battery.authToken` | Token für den Zugriff auf die Sonnen API |
| `battery.inverter.max.watts` | Maximale Lade-/Entladeleistung des Wechselrichters in Watt |
| `battery.max.capacity.wh` | Nutzbare oder konfigurierte Batteriekapazität in Wattstunden |

👉 **Diese Werte müssen zu deiner realen Installation passen.**  
Falsche Kapazitäten, falsche Leistungswerte oder eine falsche API-Adresse können zu unzuverlässigen Ladeentscheidungen führen.

---

### Sonnen / Etrel INCH per Modbus TCP

`WALLBOX_MODE` wählt `ECO` (nur PV-Überschuss), `SMART` (PV plus zulässige Batterieentladung) oder `POWER` (maximaler konfigurierter Strom, Standardmodus mit 16 A). ECO und SMART stoppen bei weniger als `wallbox.min-current-a` erst nach `wallbox.resume-hold-seconds`; sobald mindestens der Mindeststrom verfügbar ist, starten sie ohne zusätzliche Wiederanlauf-Verzögerung. POWER ignoriert PV- und Batterie-Leistungsgrenzen, außer wenn das optionale Hauslimit aktiv ist. `battery.grid.import.limit.watts` begrenzt ausschließlich die Batterie-Steuerung. `wallbox.grid-limit-watts` (0 = aus) begrenzt in allen Modi den Haus-Netzbezug anhand der Hauslast ohne Wallbox sowie PV- und gemessener Batterieleistung. `wallbox.phases`, `wallbox.voltage`, `wallbox.min-current-a` und `wallbox.max-current-a` bestimmen die Stromumrechnung; EV- und Installationsgrenzen gelten zusätzlich. Die PV-Quelle folgt `kostal.pv-source` mit Sonnen-Fallback. Für positive Sollwerte muss an der Box Power/Fast eingestellt sein. IR 53 wird als Rohwert angezeigt; seine Bedeutung wird nicht angenommen.

Beim Start gilt die Reihenfolge gespeicherte GUI-Auswahl > `WALLBOX_MODE` > Property-Default (`POWER`). UI-Wechsel werden in `wallbox.config-file` gespeichert, ohne vorhandene Property-Platzhalter zu ersetzen. Das Dashboard zeigt den vom Regel-Thread aktiven Modus, den zuletzt geschriebenen HR-8-Sollwert und den von der Box gemeldeten angewendeten Strom.

Die Phasenzahl für die Stromberechnung kommt zuerst aus den Phasenströmen (Phase aktiv bei >1 A), dann aus IR 1 (`0` = 3 Phasen, `1`–`3` = je eine Phase L1–L3, `5` = 2 Phasen), IR 1023 und zuletzt `wallbox.phases`. Das Dashboard zeigt den gemessenen Phasenverbrauch und den Rohwert von IR 1 separat. IR 4 wird per FC 04 ab Adresse 4 als zwei Big-Endian-Worte (Float32) gelesen; das DEBUG-Log enthält beide Rohwörter in Hex und den dekodierten Wert. Die Diagnose zeigt außerdem IR 2 als EV-Maximalstrom, IR 1028 als Box-Stromdeckel sowie IR 53 als Rohwert und Text (`1` Fast, `2` Smart, sonst unbekannt/keine Session). Eine FC-16-Bestätigung von HR 8 bestätigt die Modbus-Annahme des Schreibens, nicht den tatsächlich fließenden Strom. Bei einer Abweichung werden IR 4 und die drei Phasenströme nach mehreren Regelzyklen gemeinsam geloggt. Ein bisheriger Live-Befund im Fast-Modus mit abweichendem HR-8-Sollwert und höheren Phasenströmen belegt nicht, dass Fast niedrigere Sollwerte ignoriert; dieses Verhalten bleibt mit den neuen Rücklese-Diagnosen zu prüfen. Exception 12 („Current setpoint too high“) löst einen frischen Read aus; der Retry bleibt auf den gemeldeten Box- und EV-Grenzen.

Beim Aktivieren und beim Start mit eingeschalteter Steuerung beginnt Zeus mit HR 8 = 0 A. Erst wenn die Wallbox diesen Schreibzugriff bestätigt, liest Zeus die Messwerte und berechnet den ersten Ladesollwert.

Im Dashboard unter **Wallbox** lassen sich die Steuerung einschalten, der Lademodus wechseln und die beiden Smart-Grenzen bearbeiten. Änderungen lösen sofort einen neuen Regelzyklus aus. Zeus speichert die vier GUI-Werte in einem markierten Abschnitt der externen `./config/application.properties` und liest sie beim Start wieder ein. Der Dienst benötigt Schreibzugriff auf das Verzeichnis. Für eine bestehende Konfiguration an einem anderen Ort lässt sich der Pfad mit `wallbox.config-file` angeben.

Beim Ausschalten der Steuerung oder beim regulären Beenden des Dienstes fordert Zeus bei aktiver Steuerung eine Pause über HR 8 an. Nach 10 Sekunden ohne erfolgreichen Zugriff kann die Wallbox ihren eingestellten 6-A-Rückfallstrom verwenden. Das Dashboard zeigt den Zeitpunkt des letzten vollständigen erfolgreichen Reads und kennzeichnet einen möglichen Rückfall. Die drei Istströme sind die Messwerte für L1, L2 und L3.

Bei Modbus-Fehlern bleiben die letzten gültigen Messwerte sichtbar. Zeus versucht, den Sollstrom auf 0 A zu setzen und startet automatische Wiederverbindungsversuche. Nach drei aufeinanderfolgenden Fehlern wird die Verbindung mit Backoff neu aufgebaut. Ist die Pause unbestätigt, wartet Zeus mindestens 7 Sekunden und versucht im nächsten Regelzyklus, spätestens nach 8 Sekunden, erneut 0 A; positive Sollwerte bleiben bis zur bestätigten Pause und einem vollständigen neuen Messzyklus gesperrt. Eine Modbus-Bestätigung quittiert das Schreiben. Den tatsächlich fließenden Strom zeigen die Phasenmesswerte.

Mit `WALLBOX_ENABLED=true` übernimmt Zeus die Stromvorgabe an Anschluss 1. In der Wallbox Power/Fast wählen, die Sonnen-Systemkommunikation eingeschaltet lassen und die Kommunikationsüberwachung auf 10 s mit 6 A Rückfallstrom stellen. Zeus hält eine persistente Modbus-TCP-Verbindung und schreibt höchstens alle 8 s den Sollstrom in HR 8. Connect-Timeout, Read-Timeout und Fehlergrenze lassen sich über `wallbox.connect-timeout-ms` (2000 ms), `wallbox.read-timeout-ms` (2500 ms) und `wallbox.max-errors-before-reconnect` (3) einstellen. Während der ersten ca. 45 s kann die Fahrzeugerkennung die Übernahme verzögern.

`WALLBOX_MODE` wählt `ECO` (Dach-PV), `SMART` (Dach-PV plus Hausspeicher bis zur konfigurierten SoC-Reserve) oder `POWER` (bis 11 kW). Unter 6 A stoppt Zeus nach der konfigurierten Stopp-Hysterese; bei ausreichender Leistung startet die Regelung ohne zusätzliche Startverzögerung. Nutze `deployment/application.properties.example` als Vorlage für die private `config/application.properties` und setze eigene Anlagenwerte.

Bei stabiler Ladung den Protokolleintrag „Check meter wiring“ prüfen. Entspricht der Hausverbrauch ungefähr der Standortbilanz (PV + Batterieentladung − Netzeinspeisung), ist die Wallbox enthalten. Ist er um die EV-Leistung geringer, `WALLBOX_CONSUMPTION_INCLUDES_WALLBOX=false` setzen. Bei 0 W EV-Leistung ist die Zuordnung offen.

## 📊 RSOC – was ist das?

**RSOC** steht für **Relative State of Charge**.

Gemeint ist der aktuelle Ladezustand der Batterie in Prozent.

Beispiel:

- `RSOC = 20 %` → Batterie ist weitgehend leer
- `RSOC = 80 %` → Batterie ist gut gefüllt
- `RSOC = 100 %` → Batterie ist vollständig geladen

Zeus Charge Control nutzt diesen Wert, um Ladeentscheidungen zu treffen und Ladevorgänge automatisch zu beenden, wenn der Ziel-Ladezustand erreicht ist.

---

## ⚙️ Kernlogik

### 🔋 Dynamische Ladeplanung

Zeus Charge Control sucht günstige Strompreis-Zeitfenster und plant daraus mögliche Ladephasen.

Dabei werden unter anderem berücksichtigt:

- aktuelle und kommende Strompreise
- aktueller Ladezustand der Batterie
- konfigurierte Batteriekapazität
- maximale Ladeleistung
- optional verfügbare Wetterdaten

---

### 🔁 Automatische Re-Optimierung

Es gilt keine feste Preisobergrenze: Die Planung vergleicht verfügbare Preise und kann auch bei hohen Preisen Ladefenster auswählen.

Die Ladeplanung wird regelmäßig neu bewertet, zum Beispiel:

- bei neuen Preisupdates
- stündlich per Scheduler
- bei relevanten Zustandsänderungen der Batterie

So kann die Anwendung auf veränderte Preise, API-Daten oder Batteriezustände reagieren.

---

### 🧹 Automatische Entfernung geplanter Ladefenster

Zeus Charge Control kann geplante oder aktive Ladefenster automatisch entfernen beziehungsweise beenden, wenn sie nicht mehr sinnvoll sind.

Das kann zum Beispiel passieren, wenn:

- der gewünschte Ziel-RSOC bereits erreicht wurde
- ein Ladefenster abgelaufen ist
- neue Strompreise eine bessere Planung ermöglichen
- die Batterie oder API andere Zustände meldet als erwartet

**Warum ist das wichtig?**  
Ohne diese Bereinigung könnten alte oder überholte Ladeentscheidungen bestehen bleiben. Die automatische Entfernung sorgt dafür, dass die Planung aktuell bleibt und nicht unnötig weitergeladen wird.

---

## 📦 Voraussetzungen

- Java 21
- Maven 3.9 oder neuer
- PV-Batteriespeicher mit Sonnen API v2
- gültige lokale Konfiguration für Batterie, Strompreise und optionale Dienste

---

## ▶️ Build & Start

### Build

```bash
mvn clean package
```

Erzeugt ein vollständiges, ausführbares JAR unter `target/zeus-charge-control-4.0.0.jar`, einschließlich Java-Abhängigkeiten, Templates, CSS, JavaScript und sämtlicher Sprachdateien. `mvn clean verify` kann weiterhin für den vollständigen Maven-Lifecycle verwendet werden.

### Start im Entwicklungsmodus

Details zu den opt-in Wallbox- und KOSTAL-Funktionen: [Wallbox and KOSTAL configuration](./docs/kostal-integration.md).

```bash
mvn spring-boot:run
```

### Start als JAR

```bash
java -jar target/zeus-charge-control-4.0.0.jar
```

Die interne Konfiguration liegt unter `BOOT-INF/classes/application.properties`. Beim Austausch gegen eine vorhandene Konfiguration bleiben alle GUI-Texte im JAR verfügbar: Sie liegen zusammen mit den bisherigen Texten in `messages.properties`, `messages_de.properties` und `messages_en.properties`; `spring.messages.basename=messages` genügt weiterhin. Alternativ kann die eigene Konfiguration extern unter `config/application.properties` im Arbeitsverzeichnis liegen.

### Deployment-Paket erstellen

```powershell
.\scripts\package.ps1 -Ui -SmokeTest
```

Erstellt nach erfolgreichem Build und Tests **`dist/zeus-charge-control-4.0.0.zip`** samt SHA-256-Datei. Enthalten sind das ausführbare JAR, Windows-/Bash-Startskripte, Konfigurationsvorlage, Lizenzen und Build-Nachweis. Der Smoke-Test entpackt das echte ZIP in einen Pfad mit Leerzeichen und startet beide Launcher isoliert ohne Batterie-Zugriff. Für diesen zusätzlichen Test werden unter Windows Node.js ≥ 22 und Git Bash benötigt.

Auf dem Zielsystem genügt Java 21 oder neuer. ZIP entpacken, `config/application.properties.example` nach `config/application.properties` kopieren und anpassen, dann `start.bat` oder `bash start.sh` ausführen. Die Startskripte überschreiben keine Zugangsdaten. [Deployment-Anleitung](./docs/deployment.md)

---

## 🖥️ Web-UI

Öffne nach dem Start **http://localhost:8080/charging-status**; `/` leitet ebenfalls zur Übersicht weiter.

- **Speicherübersicht:** Ladezustand, verfügbare kWh, saisonales Ladeziel sowie Solarproduktion, Hausverbrauch und Batterieleistung.
- **Eindeutige Aktionen:** Automatik, Speicher pausieren und Netzladen starten/beenden. Netzladen benötigt eine ausdrückliche Bestätigung ohne Zeitlimit. Zustände wechseln nach der Rückmeldung des Dienstes.
- **Nachtruhe:** Aktivierung und ganze Stunden von 0 bis 23; Start und Ende müssen verschieden sein. Ein Fenster über Mitternacht ist möglich. Änderungen gelten bis zum Serverneustart; die dauerhaften Vorgaben stehen in `night.start`, `night.end` und `battery.nightChargingIdle`.
- **Preise & Planung:** Preisverlauf, Ladeplan und die fünf günstigsten zukünftigen Zeiträume, jeweils als Diagramm oder Tabelle. Die Preislinie zeigt die tatsächlichen Preisintervalle als Stufen. Abgelaufene Zeitfenster erscheinen nicht als nächste Preisempfehlung.
- **Preisbasis:** Börsenpreis netto oder Gesamtpreis mit konfigurierten Aufschlägen und Mehrwertsteuer, einheitlich für Karten, Tabellen, Diagramme und Bestätigungsdialog. Die Umschaltung verändert nur die Anzeige, nicht die Ladeoptimierung. Tarifwerte in `application.yml` prüfen.
- **Live-Status:** Aktualisierung alle zehn Sekunden ohne Seiten-Neuladen. Nicht gespeicherte Eingaben bleiben erhalten. Verbindungsprobleme werden sichtbar; Steueraktionen sind bis zur Wiederverbindung gesperrt. Fehlende Messwerte erscheinen als `—`.
- **Themes:** Nord, Midnight, Forest, Solar und Ember. Theme, Preisbasis, gewählter Tab und Tabellenansicht bleiben lokal im Browser gespeichert. Die bisherigen Theme-Schlüssel bleiben kompatibel.
- **Bedienbarkeit:** Deutsch/Englisch, Tastaturnavigation, sichtbarer Fokus, beschriftete Schalter, reduzierte Bewegung und Layouts von Smartphone bis Desktop.

### Lokal prüfen und ansehen

Java 21 mit aktuellem Sicherheits-Patch und Maven ≥ 3.9 verwenden. **`mvn -version` muss das gewünschte JDK zeigen**; ein altes `JAVA_HOME` kann trotz aktuellem `java -version` Java 17 auswählen.

Unter Windows wählt dieses Skript ein passendes JDK für den Prozess aus (zuerst `.tools/jdk21`, danach `JAVA_HOME` und Java im `PATH`):

```powershell
.\scripts\verify.ps1 -Clean       # Backend, Produktionskontext, HTTP/JSON und Datenbank
.\scripts\verify.ps1 -Ui          # zusätzlich Chromium-GUI-Tests und Screenshots
.\scripts\verify.ps1 -Preview     # isolierte Vorschau mit Beispieldaten auf Port 18080
```

Vorschau: **http://127.0.0.1:18080/charging-status?lang=de**. Sie verwendet ausschließlich simulierte Dienste und verbindet sich mit keiner Batterie. Mit `Strg+C` beenden. Diese Vorschau und ihre Test-Endpunkte sind nicht im ausgelieferten JAR enthalten.

Plattformübergreifend, mit korrekt gesetztem `JAVA_HOME` und Node.js ≥ 22 für die GUI-Tests:

```bash
mvn -B clean verify
npm ci --ignore-scripts
npx playwright install chromium
npm run test:ui
npm run format:check
```

Unter Linux bei Bedarf `npx playwright install --with-deps chromium` verwenden. Der Playwright-Server bindet nur an `127.0.0.1:18080`; der Port muss frei sein. Tests laufen auf echten Spring-/Thymeleaf-Seiten mit simulierten Hardwarediensten. Automatische Accessibility-Checks ergänzen die Funktionsprüfungen; sie ersetzen keine vollständige manuelle Barrierefreiheitsprüfung.

Screenshots neu erzeugen: `npm run screenshots`. Alle zehn Aufnahmen liegen in [`images/screenshots`](./images/screenshots) und sind als Beispieldaten gekennzeichnet. Browserbibliotheken werden lokal ausgeliefert; Node.js und ein CDN sind für den Produktivbetrieb nicht erforderlich. Nach Paketupdates die eingebundenen Dateien mit `npm run vendor` synchronisieren.

---

## 🛡️ Stabilität & Fehlertoleranz

Die Anwendung ist darauf ausgelegt, bei externen Problemen möglichst kontrolliert zu reagieren.

Typische externe Fehlerquellen sind:

- nicht erreichbare Batterie-API
- fehlende oder fehlerhafte Strompreisdaten
- Ausfälle externer Dienste
- Netzwerkprobleme
- unvollständige Konfiguration

Trotzdem gilt:  
**Automatisierte Steuerung ersetzt kein Monitoring.** Der Betreiber sollte die Anlage regelmäßig prüfen.

---

## ⚠️ Rechtlicher Hinweis / Disclaimer

### Kein Produkt der Sonnen GmbH

Dieses Projekt steht in **keiner Verbindung zur Sonnen GmbH**.

Die Software:

- wurde **nicht** von der Sonnen GmbH entwickelt
- wird **nicht** von der Sonnen GmbH bereitgestellt
- wird **nicht** von der Sonnen GmbH unterstützt
- ist **kein offizielles Produkt** der Sonnen GmbH
- steht in **keinem Affiliate-, Partner- oder Sponsoring-Verhältnis** zur Sonnen GmbH

Alle Marken, Produktnamen und Unternehmensnamen gehören den jeweiligen Rechteinhabern und werden ausschließlich beschreibend verwendet.

Die Verwendung der Sonnen API erfolgt auf Grundlage der vom jeweiligen Betreiber eingerichteten und zugänglichen Schnittstellen. Der Betreiber ist selbst dafür verantwortlich, die Nutzungsbedingungen, technischen Vorgaben und rechtlichen Rahmenbedingungen der eingesetzten Schnittstellen einzuhalten.

---

### Nutzung auf eigene Verantwortung

Die Nutzung von Zeus Charge Control erfolgt **ausschließlich auf eigene Verantwortung**.

Der Betreiber der Installation ist insbesondere verantwortlich für:

- korrekte Konfiguration der Anwendung
- Prüfung der technischen Eignung der eigenen Anlage
- Überwachung des Betriebs
- Bewertung der automatisierten Ladeentscheidungen
- Einhaltung gesetzlicher, vertraglicher und technischer Vorgaben
- Prüfung möglicher Auswirkungen auf Garantie, Gewährleistung, Versicherung oder Herstellervorgaben

Automatisierte Steuerungen können unerwartetes Verhalten verursachen, insbesondere bei:

- fehlerhaften Konfigurationen
- falschen Leistungs- oder Kapazitätswerten
- unvollständigen oder fehlerhaften Marktdaten
- API-Ausfällen
- Kommunikationsproblemen
- Änderungen an externen Schnittstellen
- Firmware- oder Softwareänderungen am Batteriesystem

---

### Keine Gewährleistung

Diese Software wird **„wie sie ist“ („as is“) und ohne Gewährleistung** bereitgestellt.

Es wird insbesondere keine Gewähr übernommen für:

- Richtigkeit, Vollständigkeit oder Aktualität von Marktdaten
- Verfügbarkeit externer APIs oder Dienste
- fehlerfreie oder unterbrechungsfreie Funktion
- wirtschaftliche Vorteile oder Stromkosteneinsparungen
- technische Eignung für einen bestimmten Zweck
- Kompatibilität mit bestimmten Anlagen, Firmwareständen oder API-Versionen

---

### Haftungsbeschränkung

Soweit gesetzlich zulässig, ist eine Haftung der Projektbeteiligten für Schäden ausgeschlossen, die aus der Nutzung, Fehlkonfiguration oder Nichtverfügbarkeit der Software entstehen.

Dies umfasst insbesondere:

- Datenverluste
- Fehlfunktionen der Ladeplanung
- wirtschaftliche Nachteile
- höhere Stromkosten
- technische Störungen
- Betriebsunterbrechungen
- Schäden durch fehlerhafte oder unerwartete Steuerungsentscheidungen

Gesetzlich zwingende Haftungstatbestände bleiben unberührt.

---

### Kein Einsatz in sicherheitskritischen Bereichen

Zeus Charge Control ist **nicht für sicherheitskritische, medizinische, netzrelevante oder anderweitig kritische Anwendungen geeignet**.

Die Anwendung dient der privaten beziehungsweise eigenverantwortlichen Optimierung eines unterstützten PV-Batteriespeichers. Sie ersetzt keine zertifizierte Steuerung, kein Energiemanagementsystem mit Sicherheitsfunktion und keine fachliche Prüfung durch qualifizierte Personen.

---

## 📄 Lizenz

Dieses Projekt steht unter der **Apache License 2.0**.

Details findest du in der Lizenzdatei des Projekts.

---

# 🇬🇧 English Documentation

## ⚠️ Important Notice

**Zeus Charge Control currently supports only PV battery storage systems with Sonnen API v2.**

Other battery storage systems, inverters or energy management systems are currently not supported.

👉 **Production use may be possible, but it is always at the operator's own responsibility.**  
Whether this application is suitable for a specific installation depends on the actual system, configuration, available interfaces and ongoing monitoring by the operator.

Configuration, monitoring and evaluation of automated charging decisions are the responsibility of the respective system operator.

---

## 🧠 What is Zeus Charge Control?

**Zeus Charge Control** is a **Java/Spring Boot application** for intelligent charging control of a PV battery storage system.

The application uses dynamic electricity prices, the current battery state and optional weather data to identify low-price charging windows and control the battery accordingly.

In short:  
**When electricity is particularly cheap, Zeus Charge Control can charge the battery in a targeted way – provided your configuration and installation are suitable.**

---

## 👥 Who is this project for?

Zeus Charge Control is intended for technically experienced operators of supported PV battery storage systems with **Sonnen API v2** who want to use dynamic electricity prices and automate charging decisions under their own responsibility.

The project is especially suitable for users who:

- operate a supported Sonnen PV battery storage system
- have access to the Sonnen API v2 of their installation
- want to use dynamic electricity tariffs or market price data
- can safely evaluate technical configuration parameters
- are willing to actively monitor the application during operation

This project is not intended for users expecting a universal plug-and-play solution without technical verification, configuration or monitoring.

---

## 🔋 Supported Basis

Currently technically supported:

- ✅ PV battery storage systems with **Sonnen API v2**

Currently not supported:

- ❌ other battery storage systems without Sonnen API v2
- ❌ universal inverter control
- ❌ full energy management systems
- ❌ safety-critical or grid-relevant control functions

---

## 🚫 What Zeus Charge Control does not do

Zeus Charge Control is not a full energy management system and does not replace professional verification of your own installation.

The application:

- does not guarantee electricity cost savings
- does not automatically detect every technical detail of your installation
- does not replace monitoring by the operator
- is not official software from Sonnen GmbH
- is not intended for safety-critical, medical or grid-relevant control tasks
- does not replace certified control systems or qualified professional assessment

---

## 🚀 Features

- ⚡ Dynamic charging planning based on electricity prices
- 📉 Optimization for low-price market periods
- 🔋 Control logic that considers the current battery state
- 🌤️ Optional weather integration
- 🎛️ Manual control via the web UI
- 🎨 Multiple modern UI themes
- 🔄 Event-based and scheduled re-optimization
- 🛡️ Robust behavior in case of API or data problems

---

## 🔧 Battery Configuration

The application must be adapted to your real battery installation. The battery address, authentication and technical performance values are especially important.

```properties
battery.url=${BATTERY_URL:}
battery.authToken=${BATTERY_AUTH_TOKEN:}

battery.inverter.max.watts=${BATTERY_INVERTER_MAX_WATTS:4600}
battery.max.capacity.wh=${BATTERY_MAX_CAPACITY_WH:10000}
```

### What do these values mean?

| Setting | Meaning |
|---|---|
| `battery.url` | Address of your Sonnen battery in the local or reachable network |
| `battery.authToken` | Token for accessing the Sonnen API |
| `battery.inverter.max.watts` | Maximum charging/discharging power of the inverter in watts |
| `battery.max.capacity.wh` | Usable or configured battery capacity in watt-hours |

👉 **These values must match your real installation.**  
Incorrect capacities, incorrect power values or a wrong API address may result in unreliable charging decisions.

---

### Sonnen / Etrel INCH over Modbus TCP

On enable, including startup with control enabled, Zeus first writes HR 8 = 0 A. Only after the charger acknowledges this write does Zeus read measurements and calculate its first charging target.

Use **Wallbox** on the dashboard to enable control, switch modes and edit the two Smart limits. Changes trigger a fresh control cycle immediately. Zeus saves the four GUI settings in a marked section of external `./config/application.properties` and reloads them on startup. The service needs write access to the directory. Set `wallbox.config-file` to use an existing configuration in another location.

Switching control off requests a pause via HR 8 and stops polling. After 10 seconds without a successful access the charger may use its configured 6 A fallback. The dashboard shows the time of the last complete successful read and flags a possible fallback. The three measured currents correspond to L1, L2 and L3.

An orderly shutdown also requests a pause when control is enabled. On Modbus errors, Zeus keeps the last valid charger readings visible and retries automatically. After three consecutive errors, it reconnects with backoff. If a pause is unconfirmed, Zeus waits at least 7 seconds and requests 0 A again in the next control cycle, no later than 8 seconds; positive targets remain blocked until the pause is acknowledged and a complete new measurement cycle succeeds. A Modbus acknowledgement confirms the register write. The phase measurements show the current actually flowing.

Set `WALLBOX_ENABLED=true` to control connector 1. Select Power/Fast in the charger, keep Sonnen system communication enabled, and set the web UI communication monitor to 10 s with a 6 A fallback. Zeus keeps one persistent Modbus TCP connection and writes HR 8 at most every 8 s. Configure the connect timeout, read timeout and error threshold with `wallbox.connect-timeout-ms` (2000 ms), `wallbox.read-timeout-ms` (2500 ms) and `wallbox.max-errors-before-reconnect` (3). The EV can take about 45 s to apply a setpoint at session start.

Set `WALLBOX_MODE` to `ECO` for PV surplus only, `SMART` for PV plus allowed battery discharge, or `POWER` for the configured maximum current (16 A by default and the default mode). Eco and Smart stop below `wallbox.min-current-a` only after `wallbox.resume-hold-seconds`; when enough power is available, they start without an additional resume delay. Power ignores PV and battery power limits unless the optional site limit is active. `battery.grid.import.limit.watts` controls battery behavior only. `wallbox.grid-limit-watts` (0 = off) limits site grid import in every mode using house consumption without the wallbox, PV and measured battery power. Configure current conversion with `wallbox.phases`, `wallbox.voltage`, `wallbox.min-current-a` and `wallbox.max-current-a`; EV and installation limits still apply. The PV source follows `kostal.pv-source` with Sonnen fallback. The charger must be set to Power/Fast for positive targets. IR 53 is shown as a raw diagnostic value only; its meaning is not assumed. Start from `deployment/application.properties.example` and provide your own charger hostname, Sonnen URL and site settings in private `config/application.properties`.

At startup, mode precedence is saved GUI selection > `WALLBOX_MODE` > property default (`POWER`). UI changes are saved to `wallbox.config-file` without replacing existing property placeholders. The dashboard shows the active mode from the control thread, the last HR 8 target written and the current reported by the charger.

For current calculation, Zeus first counts phase currents above 1 A, then falls back to IR 1 (`0` = three phases, `1`–`3` = one phase on L1–L3, `5` = two phases), IR 1023 and finally `wallbox.phases`. The dashboard shows measured phase use and the raw IR 1 value separately. IR 4 is read with FC 04 from address 4 as two big-endian Float32 words; the DEBUG log includes both raw words in hex and the decoded value. Diagnostics also show IR 2 as EV maximum current, IR 1028 as the charger current ceiling, and IR 53 as both its raw value and a label (`1` Fast, `2` Smart, otherwise unknown/no session). An FC 16 acknowledgement for HR 8 confirms Modbus accepted the write, not the current actually flowing. If readback differs after multiple cycles, Zeus logs IR 4 and all three phase currents together. A live Fast-mode observation with a different HR 8 target and higher phase currents does not prove Fast ignores lower setpoints; validate that behavior with the new readback diagnostics. Exception 12 (“Current setpoint too high”) triggers a fresh read; the retry respects the reported charger and EV limits.

During stable charging, compare the values in the “Check meter wiring” log entry. If consumption matches the site balance (PV + signed battery discharge − signed grid export), leave `WALLBOX_CONSUMPTION_INCLUDES_WALLBOX=true`. If consumption is lower by approximately the measured EV load, set it to `false`. An idle EV gives the same result for either wiring.

## 📊 RSOC – what does it mean?

**RSOC** stands for **Relative State of Charge**.

It describes the current battery state of charge as a percentage.

Example:

- `RSOC = 20 %` → battery is mostly empty
- `RSOC = 80 %` → battery is well charged
- `RSOC = 100 %` → battery is fully charged

Zeus Charge Control uses this value to make charging decisions and to stop charging automatically once the target state of charge has been reached.

---

## ⚙️ Core Logic

### 🔋 Dynamic Charging Planning

Zeus Charge Control searches for low-price electricity windows and plans possible charging phases based on them.

Among other factors, the application considers:

- current and upcoming electricity prices
- current battery state of charge
- configured battery capacity
- maximum charging power
- optional weather data

---

### 🔁 Automatic Re-Optimization

There is no fixed price cap: the planner compares available prices and can select charging windows even when prices are high.

The charging plan is regularly re-evaluated, for example:

- when new price data becomes available
- hourly via scheduler
- when relevant battery state changes occur

This allows the application to react to changing prices, API data or battery states.

---

### 🧹 Automatic Removal of Planned Charging Windows

Zeus Charge Control can automatically remove or stop planned or active charging windows when they are no longer useful.

This may happen, for example, when:

- the desired target RSOC has already been reached
- a charging window has expired
- new electricity prices allow a better plan
- the battery or API reports unexpected states

**Why is this important?**  
Without this cleanup, outdated or obsolete charging decisions could remain active. Automatic removal helps keep the plan current and prevents unnecessary continued charging.

---

## 📦 Requirements

- Java 21
- Maven 3.9 or newer
- PV battery storage system with Sonnen API v2
- valid local configuration for battery, electricity prices and optional services

---

## ▶️ Build & Start

### Build

```bash
mvn clean package
```

Creates a complete executable `target/zeus-charge-control-4.0.0.jar`, including Java dependencies, templates, CSS, JavaScript and all translations. `mvn clean verify` remains available for the full Maven lifecycle.

### Start in development mode

```bash
mvn spring-boot:run
```

### Start as JAR

```bash
java -jar target/zeus-charge-control-4.0.0.jar
```

The internal configuration is `BOOT-INF/classes/application.properties`. Replacing it with an existing configuration preserves all GUI translations: they share the existing `messages.properties`, `messages_de.properties` and `messages_en.properties` bundles, so `spring.messages.basename=messages` still works. Alternatively, keep operator settings externally in `config/application.properties` relative to the working directory.

### Create a deployment package

```powershell
.\scripts\package.ps1 -Ui -SmokeTest
```

After a successful build and verification, this produces **`dist/zeus-charge-control-4.0.0.zip`** and its SHA-256 file. The ZIP contains the executable JAR, Windows/Bash launchers, configuration example, licenses and build metadata. The extra smoke test extracts the real archive into a path containing spaces and runs both launchers with isolated services. On Windows, this check needs Node.js ≥ 22 and Git Bash.

The target needs only Java 21 or newer. Extract, copy `config/application.properties.example` to `config/application.properties`, review the settings and run `start.bat` or `bash start.sh`. Launchers preserve existing credentials and environment variables. [Deployment instructions](./docs/deployment.md)

---

## 🖥️ Web UI

Open **http://localhost:8080/charging-status** after starting the application; `/` redirects to the dashboard.

- **Storage overview:** state of charge, remaining kWh, seasonal target, solar production, household consumption and battery power.
- **Clear actions:** automatic control, pause battery and start/stop grid charging. Starting requires explicit confirmation without a timeout. Controls reflect the service's acknowledged state.
- **Quiet hours:** enable the night hold and set distinct whole hours from 0 to 23, including overnight windows. Changes last until server restart. Persistent defaults remain `night.start`, `night.end` and `battery.nightChargingIdle`.
- **Prices & planning:** stepped interval prices, charging schedules and the five cheapest future windows, each available as a chart or table. Expired intervals are excluded from the next-price recommendation.
- **Price basis:** net market price or total price including configured surcharges and VAT, consistently applied to cards, tables, charts and confirmation. This is a display setting and does not change the charging optimizer. Review your tariff values in `application.yml`.
- **Live updates:** refresh every ten seconds without page navigation; unsaved input survives polling. A visible connection warning disables commands until recovery. Missing readings appear as `—`.
- **Themes:** Nord, Midnight, Forest, Solar and Ember. Theme, price basis, active tab and table view persist in browser storage. Previous theme keys remain compatible.
- **Accessibility:** German/English, keyboard navigation, visible focus, labelled switches, reduced motion and responsive layouts.

### Local verification and preview

Use Java 21 with current security updates and Maven ≥ 3.9. Check **`mvn -version`**, because an outdated `JAVA_HOME` may select Java 17 even when `java -version` shows Java 21.

On Windows, the helper selects a suitable JDK for the process, preferring `.tools/jdk21`, then `JAVA_HOME` and Java on `PATH`:

```powershell
.\scripts\verify.ps1 -Clean       # backend, production wiring, HTTP/JSON and database
.\scripts\verify.ps1 -Ui          # also Chromium UI tests and screenshots
.\scripts\verify.ps1 -Preview     # isolated sample-data preview on port 18080
```

Preview: **http://127.0.0.1:18080/charging-status?lang=en**. It uses simulated services and never connects to a battery. Stop with `Ctrl+C`. The preview and its test endpoints are excluded from the release JAR.

Cross-platform, with the correct `JAVA_HOME` and Node.js ≥ 22 for browser tests:

```bash
mvn -B clean verify
npm ci --ignore-scripts
npx playwright install chromium
npm run test:ui
npm run format:check
```

On Linux, use `npx playwright install --with-deps chromium` if browser system packages are missing. Port `127.0.0.1:18080` must be available. Tests exercise real Spring/Thymeleaf pages with simulated hardware services. Automated accessibility checks complement functional tests and do not constitute a complete manual accessibility audit.

Regenerate all ten labelled sample screenshots with `npm run screenshots`; files are in [`images/screenshots`](./images/screenshots). Browser dependencies ship locally, so production requires neither Node.js nor a CDN. Run `npm run vendor` after browser package upgrades to update checked-in assets.

---

## 🛡️ Stability & Fault Tolerance

The application is designed to react as safely and predictably as possible to external problems.

Typical external error sources include:

- unreachable battery API
- missing or incorrect electricity price data
- outages of external services
- network problems
- incomplete configuration

Nevertheless:  
**Automated control does not replace monitoring.** The operator should check the system regularly.

---

## ⚠️ Legal Notice / Disclaimer

### Not a product of Sonnen GmbH

This project is **not affiliated with Sonnen GmbH**.

The software:

- was **not** developed by Sonnen GmbH
- is **not** provided by Sonnen GmbH
- is **not** supported by Sonnen GmbH
- is **not** an official product of Sonnen GmbH
- has **no affiliate, partner or sponsorship relationship** with Sonnen GmbH

All trademarks, product names and company names belong to their respective owners and are used for descriptive purposes only.

Use of the Sonnen API is based on the interfaces configured and made accessible by the respective operator. The operator is responsible for complying with the terms of use, technical requirements and legal framework of the interfaces being used.

---

### Use at your own responsibility

Use of Zeus Charge Control is **entirely at your own responsibility**.

The system operator is especially responsible for:

- correct configuration of the application
- verifying the technical suitability of the installation
- monitoring operation
- evaluating automated charging decisions
- complying with legal, contractual and technical requirements
- checking possible effects on warranty, insurance or manufacturer requirements

Automated control may cause unexpected behavior, especially in case of:

- incorrect configuration
- incorrect power or capacity values
- incomplete or incorrect market data
- API outages
- communication problems
- changes to external interfaces
- firmware or software changes in the battery system

---

### No warranty

This software is provided **“as is” and without warranty**.

In particular, no warranty is given for:

- correctness, completeness or timeliness of market data
- availability of external APIs or services
- error-free or uninterrupted operation
- economic benefits or electricity cost savings
- technical suitability for a specific purpose
- compatibility with specific installations, firmware versions or API versions

---

### Limitation of liability

To the extent permitted by law, liability of the project contributors for damages resulting from the use, misconfiguration or unavailability of the software is excluded.

This includes, in particular:

- data loss
- charging plan malfunctions
- economic disadvantages
- higher electricity costs
- technical faults
- operational interruptions
- damage caused by incorrect or unexpected control decisions

Mandatory statutory liability remains unaffected.

---

### No use in safety-critical areas

Zeus Charge Control is **not suitable for safety-critical, medical, grid-relevant or otherwise critical applications**.

The application is intended for private or self-responsible optimization of a supported PV battery storage system. It does not replace certified control systems, energy management systems with safety functions or professional assessment by qualified persons.

---

## 📄 License

This project is licensed under the **Apache License 2.0**.

See the project license file for details.
