# Wallbox and KOSTAL configuration

> **DE: Die Anbindung des Wechselrichters KOSTAL und der Wallbox ETREL INCH ist EXPERIMENTELL und optional. Nutzung auf eigene Verantwortung.**
>
> **EN: Support for the KOSTAL inverter and the ETREL INCH wallbox is EXPERIMENTAL and optional. Use at your own risk.**

The wallbox and inverter features use independent switches: `wallbox.enabled` and `kostal.enabled`. Both default to `false` in the example configuration and in code. With wallbox control disabled, the application runs in monitoring mode and does not poll or control the charger. With KOSTAL disabled, PV surplus continues to use the Sonnen battery values.

## KOSTAL PLENTICORE / PIKO IQ

Enable Modbus/SunSpec (TCP) in the inverter web UI under **Settings > Modbus/SunSpec (TCP)**. Configure the local inverter host in `config/application.properties`; no Modbus credentials are required. Defaults are TCP port `1502`, Unit-ID `71`, byte order `CDAB`, and a 5-second poll interval. `ABCD` is also supported.

The client is read-only and uses Modbus function `0x03` only. It reads register `172` for total AC active power (W). Register `1066` for total DC power (W) is read for diagnostics by default and can be disabled with `kostal.read-dc-power=false`. Powermeter register `252` and battery SoC register `210` are optional and off by default. No control registers, register `2`, or battery setpoints are written.

Set `kostal.pv-source=SONNEN` to keep Sonnen values as the source for wallbox surplus calculations. `KOSTAL` selects the inverter AC value while it is fresh and valid. The app falls back automatically to Sonnen when the inverter is disabled, disconnected, stale, or reports invalid data. Values older than three poll intervals are not used for control. After three consecutive errors the persistent connection is closed and retried with backoff (2, 5, 10, then at most 30 seconds). Modbus exception `02` disables that register for the current run.

The dashboard shows AC/DC PV readings, the active PV source, connection state, and the timestamp of the last valid value. Invalid placeholder hosts or out-of-range settings stop the affected device integration and log a clear configuration error; the application continues to run.

The example configuration contains placeholders only. Keep the real `application.properties` outside source control; `.gitignore` excludes it.
