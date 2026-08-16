package com.elastiflix.controller.web;

import com.elastiflix.model.SearchMode;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Renders the landing page with the search mode picker. */
@Controller
public class HomeController {

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("modes", SearchMode.values());
        // Supplied from the enum rather than hardcoded in the template, so the
        // pre-selected radio button follows the default wherever it changes.
        model.addAttribute("currentMode", SearchMode.defaultMode().name());
        return "index";
    }
}
