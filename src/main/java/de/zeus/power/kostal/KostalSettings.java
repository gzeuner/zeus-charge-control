package de.zeus.power.kostal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Read-only KOSTAL Modbus settings. Validation is deliberately non-fatal to application startup. */
@Component
record KostalSettings(
        @Value("${kostal.enabled:false}") boolean enabled,
        @Value("${kostal.host:}") String host,
        @Value("${kostal.port:1502}") int port,
        @Value("${kostal.unit-id:71}") int unitId,
        @Value("${kostal.byte-order:CDAB}") String byteOrder,
        @Value("${kostal.poll-interval-ms:5000}") int pollIntervalMs,
        @Value("${kostal.connect-timeout-ms:2000}") int connectTimeoutMs,
        @Value("${kostal.read-timeout-ms:3000}") int readTimeoutMs,
        @Value("${kostal.read-powermeter:false}") boolean readPowermeter,
        @Value("${kostal.read-battery:false}") boolean readBattery,
        @Value("${kostal.read-dc-power:true}") boolean readDcPower,
        @Value("${kostal.pv-source:SONNEN}") String pvSource,
        @Value("${kostal.max-pv-power-w:30000}") double maxPvPowerW) {

    String validationError() {
        if (host == null || host.isBlank() || host.trim().matches("<.*>")) return "host is empty or still a placeholder";
        if (port < 1 || port > 65535) return "port must be between 1 and 65535";
        if (unitId < 0 || unitId > 255) return "unit-id must be between 0 and 255";
        if (!"CDAB".equalsIgnoreCase(byteOrder) && !"ABCD".equalsIgnoreCase(byteOrder)) return "byte-order must be CDAB or ABCD";
        if (!"SONNEN".equalsIgnoreCase(pvSource) && !"KOSTAL".equalsIgnoreCase(pvSource)) return "pv-source must be SONNEN or KOSTAL";
        if (pollIntervalMs < 100 || connectTimeoutMs < 1 || readTimeoutMs < 1) return "poll and timeout values must be positive";
        if (!Double.isFinite(maxPvPowerW) || maxPvPowerW <= 0) return "max-pv-power-w must be positive";
        return null;
    }
}
