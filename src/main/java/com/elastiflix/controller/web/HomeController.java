package com.elastiflix.controller.web;

import com.elastiflix.model.SearchMode;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("modes", SearchMode.values());
        return "index";
    }
}
