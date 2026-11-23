package de.zeus.power.kostal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/kostal")
class KostalController {
    private final KostalInverterService service;
    KostalController(KostalInverterService service) { this.service = service; }
    @GetMapping("/status") Status status() {
        var snapshot = service.status();
        return new Status(service.enabled(), service.pvSource(), snapshot);
    }
    record Status(boolean enabled, String pvSource, KostalInverterService.Snapshot inverter) {}
}
