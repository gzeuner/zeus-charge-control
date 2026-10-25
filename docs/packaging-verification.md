# Packaging verification - 4.0.0

Verification date: **2026-10-25**.

```powershell
mvn clean package
.\scripts\package.ps1 -Ui -SmokeTest
```

The executable JAR is written to `target/`. The distribution script adds launchers, configuration example, licenses, build metadata and integrity checks under `dist/`.

| Check | Result |
| --- | --- |
| Clean Maven build | PASS; 53 tests, zero failures/errors/skips |
| Playwright desktop/mobile checks | PASS; 36 tests |
| Executable JAR, production entry point and GUI resources | PASS |
| Test/demo artifacts excluded | PASS |
| ZIP allowlist and portable entry paths | PASS |
| Complete payload SHA-256 manifest and JAR build metadata | PASS |
| Missing configuration rejected before JVM startup | PASS; Windows and Git Bash, exit code 2 |
| Launchers run from another working directory and paths with spaces | PASS |
| External configuration and persistent H2 storage | PASS |
| German and English labels with legacy message configuration | PASS |
| Direct JAR start after replacing internal application.properties | PASS |
| CLI arguments forwarded and isolated tariff fixture loaded | PASS |
| Demo endpoints and H2 console unavailable | PASS; HTTP 404 |
| Distribution checksum verified | PASS |

Smoke tests use explicitly empty battery credentials, disposable databases and an HTTP tariff fixture bound to loopback. They run the actual production JAR, not the UI preview application. The internal configuration replacement is performed only on a disposable extracted copy. No physical battery or live tariff account is used. Git Bash checks do not establish native Linux/macOS compatibility.

The packager uses unique staging directories and an explicit file list. It excludes operator configuration, environment files, runtime databases, logs and development toolchains. It replaces an existing distribution only after all requested checks pass. Each build creates a fresh SHA-256 file beside the ZIP and `SHA256SUMS` inside it.

See [deployment.md](deployment.md) for Java 21 requirements, configuration, launch instructions and database migration guidance. Maven, Node.js and the test toolchain are not needed on the deployment host.
