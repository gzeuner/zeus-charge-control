package de.zeus.power.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/license")
public class LicenseController {

    @GetMapping
    public String getLicense(@RequestParam(name = "lang", required = false, defaultValue = "en") String lang, Model model) {
        if (lang.equals("de")) {
            return "license_de";
        } else {
            return "license_en";
        }
    }
}
