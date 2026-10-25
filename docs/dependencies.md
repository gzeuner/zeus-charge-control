# Dependency upgrade — 4.0.0

Dependency baseline for version 4.0.0, checked on 2026-10-25 against Maven Central, the npm registry and official release notes.

## Decisions

Spring Boot 3.5.11 first moved to 3.5.16 and passed the original 25 tests. The project then migrated to **4.1.2**, the current stable supported release. Spring Boot 3.5.16 is the final freely maintained 3.5 release; staying on that branch would not provide ongoing OSS maintenance. Java remains at the application's existing **21** language baseline.

The migration uses the new `webmvc`, `restclient` and `aspectj` starters, the relocated `RestTemplateBuilder`, and Jackson 3's `tools.jackson` types. `com.fasterxml.jackson.annotation` annotations intentionally remain: Jackson 3 continues using that annotation package. Browser packages are pinned in `package-lock.json`; production still serves all assets locally.

| Component | Before | After / decision |
| --- | --- | --- |
| Application | 3.0-RELEASE | **4.0.0** |
| Spring Boot | 3.5.11 | **4.1.2**, managed compatible dependency set |
| Spring Framework | Managed by Boot 3.5 | **7.0.10** |
| Jackson databind | Jackson 2 | **3.1.7** |
| Embedded Tomcat | 10.1 line | **11.0.27** |
| Apache HttpClient | Boot 3.5 managed | **5.6.5** |
| Hibernate ORM | 6.6 line | **7.4.13.Final** |
| H2 | Boot 3.5 managed | **2.4.240** |
| Thymeleaf | Boot 3.5 managed | **3.1.5.RELEASE** |
| Spring Retry | Boot 3.5 managed | **2.0.13**, explicitly pinned; existing retry behavior retained |
| Maven Enforcer | 3.6.3 after remote sync | **3.6.3**, already current |
| Bootstrap CSS / JS | 5.3.5 | **5.3.8** |
| Chart.js | 4.4.8 | **4.6.1** |
| Luxon | 3.6.1 | **3.7.2** |
| chartjs-adapter-luxon | 1.3.1 | **1.3.1**, current, retained |
| Font Awesome Free | 6.7.2 | **7.3.1**, only core CSS and solid font shipped |
| jQuery Slim | 3.7.1 | **Removed**, unused by the application and Bootstrap 5 |
| Playwright | — | **1.64.1**, development only |
| axe Playwright | — | **4.14.0**, development only |
| Prettier | — | **3.9.10**, development only |

Maven's compiler, test, resource and packaging plugins follow Spring Boot's managed versions instead of independent latest-version overrides. Maven 3.9.14 was used locally and meets the enforced ≥3.9 minimum. Node 22 is needed only for developer browser tooling; it is not part of the deployed Java application.

## Java runtime

Build and run the application with a maintained Java 21 distribution and current security updates. Set `JAVA_HOME` to the selected JDK when building with Maven. The verification baseline uses Corretto 21.0.13.

For deployment, use an up-to-date Java 21 installation on the target host. Existing file-based databases should be backed up and migration-tested separately before changing the production runtime; local persistence tests use disposable H2 databases.

## Reproducible browser assets and checks

```bash
npm ci --ignore-scripts
npm run vendor
npm audit
npm run format:check
```

`scripts/vendor-assets.cjs` copies only the required browser files and original licenses from pinned packages into `src/main/resources/static`. The original license texts are in `static/vendor-licenses`. Do not independently edit vendored minified files. Package updates also require `npm run vendor`; CI checks this correspondence.

`npm audit` reported **0 known vulnerabilities** for the installed npm dependency tree. This result is time-specific and is not a claim that every Java or browser dependency is vulnerability-free. Java components were updated as a supported Spring Boot dependency set and tested; a separate authenticated/advisory-complete Java CVE assessment was not performed.

CI runs the Java build, browser tests, formatting and asset consistency checks. Dependabot covers Maven, npm and GitHub Actions; vendored assets still need explicit synchronization after a package update.

## Primary references

- [Spring Boot 3.5.16 and end of OSS support](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)
- [Spring Boot supported versions](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions)
- [Spring Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Chart.js 4.6.1 release](https://github.com/chartjs/Chart.js/releases/tag/v4.6.1)
- [Bootstrap 5.3.8](https://blog.getbootstrap.com/2025/08/25/bootstrap-5-3-8/)
- [Corretto downloads and official checksums](https://docs.aws.amazon.com/corretto/latest/corretto-21-ug/downloads-list.html)
- [Corretto 21 changelog](https://github.com/corretto/corretto-21/blob/develop/CHANGELOG.md)
- [Playwright accessibility testing](https://playwright.dev/docs/accessibility-testing)
