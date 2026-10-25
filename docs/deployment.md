# Zeus Charge Control — Deployment

## Paket / Package

Das ZIP enthält die ausführbare Anwendung mit allen Java-Abhängigkeiten, lokal ausgelieferten GUI-Dateien, Startskripten, einer Konfigurationsvorlage und Lizenzen. Auf dem Zielsystem wird **Java 21 oder neuer** mit aktuellem Sicherheits-Patch benötigt. Maven, Node.js, die Entwicklungsumgebung und die Testdienste werden dort nicht benötigt.

The ZIP includes the executable application and its Java dependencies, local browser assets, launchers, a configuration example and licenses. The target machine needs **Java 21 or newer** with current security updates. Maven, Node.js and the development/test environment are not required there.

```text
zeus-charge-control-<version>/
  start.bat
  start.sh
  lib/zeus-charge-control.jar
  config/application.properties.example
  data/.gitkeep
  logs/.gitkeep
  licenses/browsers/...
  LICENSE
  README.md
  build-info.json
  runtime-dependencies.txt
  SHA256SUMS
```

## Start unter Windows

1. ZIP vollständig entpacken. Der Paketordner darf Leerzeichen enthalten.
2. Java prüfen: `java -version`. Falls `JAVA_HOME` gesetzt ist, muss auch dieses auf Java 21 oder neuer zeigen.
3. `config/application.properties.example` nach `config/application.properties` kopieren und prüfen: Batterie-URL/-Token, Kapazität, Leistung, saisonale Ladeziele und Tarifaufschläge müssen zur Anlage passen. Alternativ die vorgesehenen Umgebungsvariablen setzen.
4. Im Paketordner `start.bat` ausführen. Die Startdatei überschreibt keine Tokens oder vorhandenen Umgebungsvariablen.
5. `http://127.0.0.1:8080/charging-status` öffnen. Mit `Strg+C` beenden.

```powershell
Copy-Item config/application.properties.example config/application.properties
# Konfiguration bearbeiten, dann:
.\start.bat
# Beispiel für einen abweichenden Port:
.\start.bat --server.port=8081
```

## Linux / macOS

```bash
unzip zeus-charge-control-<version>.zip
cd zeus-charge-control-<version>
cp config/application.properties.example config/application.properties
# Edit configuration, then:
bash ./start.sh
# Optional: chmod +x start.sh, then ./start.sh
```

Set `JAVA_HOME` to the intended Java installation if needed. The script resolves its own directory and uses `exec`, so signals reach the JVM. All application arguments are forwarded; for example `bash ./start.sh --server.port=8081`.

## Konfiguration und Betrieb / Configuration and operation

- Die Vorlage bindet die UI an **127.0.0.1**. Für direkten LAN-Zugriff `SERVER_ADDRESS` bewusst anpassen, beispielsweise auf `0.0.0.0`, oder einen passend konfigurierten Reverse Proxy verwenden. Die Anwendung besitzt keine eigene Benutzeranmeldung. / The example binds to **127.0.0.1**. Deliberately configure a LAN binding or reverse proxy if needed; the application has no built-in user login.
- Ohne Batterie-URL und Token zeigt die Anwendung „Speicher nicht erreichbar“ und steuert keine Anlage. / Without battery URL and token, the application remains unconfigured and does not control hardware.
- Die externe Konfiguration liegt unter `config/`; sämtliche übrigen Standardwerte befinden sich im JAR. Kommandozeilenargumente überschreiben die Konfiguration. / External settings are loaded from `config/`; remaining defaults stay inside the JAR. Command-line arguments take precedence.
- Die Vorlage verwendet eine **dateibasierte H2-Datenbank** unter `data/`, aktiviert Schema-Updates und deaktiviert die H2-Webkonsole. Vor dem Einspielen vorhandener Datenbanken die Kompatibilität mit der neuen H2-/Hibernate-Version prüfen. / The example uses a **file-backed H2 database** in `data/`, enables schema updates and disables the H2 web console. Verify existing database compatibility before migration.
- `data/`, `config/application.properties` und bei Bedarf `logs/` separat sichern; keine laufende H2-Datei kopieren. Bei Updates neue Programmdateien separat entpacken und die eigene Konfiguration bewusst übernehmen. / Back up data and private configuration separately while the application is stopped. Extract upgrades separately and explicitly migrate your own configuration.
- Die Startskripte starten keinen Windows-Dienst oder systemd-Service. Für einen dauerhaften Betrieb den Startbefehl in die zum Zielsystem passende Dienstverwaltung integrieren. / Launchers run in the foreground; service management is specific to the target host.

## Einzelnes JAR / Standalone JAR

`mvn clean package` erstellt bereits das vollständige ausführbare `target/zeus-charge-control-4.0.0.jar`. Java-Abhängigkeiten, Templates, Browser-Dateien und alle Sprachdateien sind darin enthalten. Das ZIP ergänzt lediglich Startskripte, Konfigurationsvorlage, Anleitung und Nachweise.

The ordinary Maven package is a complete executable JAR. The ZIP adds launchers, an example configuration, documentation and verification metadata.

Beim Austausch der internen `BOOT-INF/classes/application.properties` bleiben die Sprachdateien unverändert im JAR. Sämtliche GUI-Texte sind in `messages.properties`, `messages_de.properties` und `messages_en.properties` enthalten. Die bisherige Einstellung `spring.messages.basename=messages` und der Spring-Boot-Standard ohne explizite Basename-Einstellung funktionieren damit weiter. Die zum Betrieb erforderlichen übrigen Einstellungen müssen in der eigenen Konfiguration enthalten sein.

Replacing the internal configuration does not remove the translations. All GUI labels use the established `messages` bundles, so existing configurations that register only `messages` remain compatible. Keep the other required operational settings in your replacement configuration.

Eine externe `config/application.properties` im Arbeitsverzeichnis kann stattdessen die internen Einstellungen überschreiben. Die Paket-Startskripte setzen das Arbeitsverzeichnis auf den Paketordner. / External `config/application.properties` can override the packaged defaults. Distribution launchers set the working directory to the package directory.

## Integrität / Integrity

Neben dem ZIP liegt eine `.zip.sha256`-Datei. Innerhalb des Pakets enthält `SHA256SUMS` die Prüfsummen der ausgelieferten Dateien, einschließlich JAR und unveränderter Konfigurationsvorlage. Eigene Laufzeitdateien sind darin absichtlich nicht enthalten.

The adjacent `.zip.sha256` file covers the ZIP. Internal `SHA256SUMS` covers the shipped payload, including the JAR and configuration example. User-created configuration and runtime files are not part of that manifest.

```powershell
Get-FileHash .\zeus-charge-control-<version>.zip -Algorithm SHA256
```

```bash
sha256sum -c zeus-charge-control-<version>.zip.sha256
cd zeus-charge-control-<version>
sha256sum -c SHA256SUMS
```

`build-info.json` documents version, build time, source revision, uncommitted source status and performed checks. `runtime-dependencies.txt` records the actual JAR contents. A dirty working tree is explicitly recorded for local review builds.

## Paket lokal erstellen / Build the package locally

From the source checkout on Windows PowerShell:

Building requires Maven ≥ 3.9 and a JDK ≥ 21. `-Ui` additionally requires Node.js ≥ 22; it installs the pinned test tools and Chromium. `-SmokeTest` uses Node and a JDK to inspect/extract the real ZIP and start it with local price fixtures and explicitly empty battery credentials. To test the Bash launcher on Windows, install Git Bash. These tools are build/test requirements only and are not bundled or needed on the deployment host.

```powershell
.\scripts\package.ps1
# Include fresh browser checks and an extracted-package launcher smoke test:
.\scripts\package.ps1 -Ui -SmokeTest
```

Das Skript führt zuerst einen sauberen Build mit Java-Tests aus, prüft den JAR-Inhalt und erstellt das ZIP samt Prüfsumme unter `dist/`. Es kopiert ausschließlich explizit ausgewählte Dateien; lokale Tokens, `.env`, Datenbanken, Logs, JDKs, Node-Module und Demo-Endpunkte werden nicht ausgeliefert. Ein vorhandenes ZIP derselben Version wird erst nach erfolgreicher Erstellung des neuen Pakets ersetzt. Es findet kein Upload oder Zielsystem-Deployment statt.

The script builds and tests first, validates the executable JAR and writes the ZIP and checksum to `dist/`. Its explicit file list excludes local credentials, databases, logs, toolchains, Node modules and demo endpoints. An existing archive is replaced only after the new package has passed its selected checks. It does not upload or install anything on a target system.
