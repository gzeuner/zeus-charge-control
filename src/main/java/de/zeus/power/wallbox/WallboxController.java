package de.zeus.power.wallbox;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/wallbox")
class WallboxController {
    private final WallboxControl control;

    WallboxController(WallboxControl control) {
        this.control = control;
    }

    @GetMapping("/status")
    WallboxControl.Status status() {
        return control.status();
    }

    @PostMapping(value = "/settings", consumes = "application/json")
    WallboxControl.Status settings(@RequestBody Map<String, Object> changes) {
        try {
            return control.update(changes);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot save wallbox configuration", e);
        }
    }
}
