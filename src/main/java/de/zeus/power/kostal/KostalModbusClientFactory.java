package de.zeus.power.kostal;

import org.springframework.stereotype.Component;

@Component
class KostalModbusClientFactory {
    KostalModbusTransport create(KostalSettings settings) {
        return new KostalModbusClient(settings.host().trim(), settings.port(), settings.unitId(),
                settings.connectTimeoutMs(), settings.readTimeoutMs());
    }
}
