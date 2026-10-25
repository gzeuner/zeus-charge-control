# Verification - Zeus Charge Control 4.0.0

Verification date: **2026-10-25**.

## Results

| Check | Result |
| --- | --- |
| Clean Maven build and standalone executable JAR | PASS |
| Backend, HTTP clients, controllers, persistence and production context | **53 tests passed**, zero failures/errors/skips |
| Playwright desktop and mobile suite | **36 tests passed** |
| Automated WCAG A/AA checks | PASS in all five themes and the confirmation dialog |
| Responsive page overflow | PASS at widths 320, 390, 768, 1024 and 1440 |
| JavaScript, CSS and template formatting | PASS |
| Browser assets reproduced from lockfile | PASS, byte-for-byte |
| Demo/test endpoints and jQuery absent from application artifact | PASS |
| npm dependency audit | Zero known vulnerabilities at verification time |
| Theme screenshots | Five desktop and five mobile captures |
| Deployment ZIP integrity and launchers | PASS; see [packaging-verification.md](packaging-verification.md) |

Verification baseline: Java 21, Maven 3.9, Node.js 22 and Playwright 1.64.1 with Chromium.

## Coverage

- Battery commands, charging management, capacity, seasonal targets, price calculations and metrics.
- Rejected charging starts preserve manual pause; reaching the target does not silently clear pause.
- Real Spring MVC, Thymeleaf, Jackson serialization, authenticated HTTP converters and Hibernate/H2 persistence.
- German and English labels with an existing configuration using `spring.messages.basename=messages`.
- Successful and rejected pause, automatic, start, stop and night-control requests.
- Quiet-hour validation; invalid requests do not change the active window.
- Charts, tables, price basis, negative prices and exclusion of expired price opportunities.
- Keyboard navigation, theme persistence, language switching and license pages.
- Confirmation cancellation, Escape and focus restoration without an automatic dismissal timeout.
- Polling updates without losing unsaved edits or submitting duplicate pending commands.
- Connection failure, stale values, disabled controls and recovery without navigation.
- Missing battery configuration, missing metrics, empty prices/schedules and invalid browser preferences.

Tests use simulated hardware-facing services and never send commands to a physical battery. Production wiring is checked separately from the UI fixture. Test-only configurations and endpoints are excluded from the application JAR.

## Standalone JAR

`mvn clean package` produces the complete executable JAR, including Java dependencies, templates, browser assets and translations. GUI messages share the established `messages.properties`, `messages_de.properties` and `messages_en.properties` bundles, preserving compatibility with existing operator configuration.

The package smoke test replaces the internal `BOOT-INF/classes/application.properties` in a disposable JAR copy and starts it with `java -jar`. It loads configuration from the classpath, uses an isolated database and loopback tariff fixture, and verifies both languages without unresolved message markers. Windows and Git Bash launchers are also checked with external configuration.

## Reproduce

With Java 21 selected through `JAVA_HOME`:

```bash
mvn -B clean verify
npm ci --ignore-scripts
npx playwright install chromium
npm run test:ui
npm run format:check
npm run vendor
git diff --exit-code -- src/main/resources/static
```

Windows PowerShell helpers:

```powershell
.\scripts\verify.ps1 -Clean -Ui
.\scripts\package.ps1 -Ui -SmokeTest
```

Java reports are written to `target/surefire-reports`; the browser report is `playwright-report/index.html`. Theme captures are listed in [screenshots.md](screenshots.md).

## Scope

No physical battery, live tariff account or real firmware was used. Chromium mobile emulation does not establish Safari/Firefox or physical-device compatibility. Automated accessibility scans do not replace human and screen-reader review. Runtime quiet-hour changes last until restart. Back up and migration-test an existing database before deploying across Hibernate/H2 major versions.
