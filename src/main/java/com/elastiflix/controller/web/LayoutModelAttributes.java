package com.elastiflix.controller.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.Year;

/**
 * Supplies the values the shared layout needs on every page.
 *
 * <p>Scoped to the web controllers so the JSON API is untouched. The footer year used to be
 * read in the template with {@code T(java.time.Year).now()} — a SpEL type reference that reaches
 * into arbitrary JVM classes from the view layer, which is neither reviewable nor testable.
 */
@ControllerAdvice(basePackages = "com.elastiflix.controller.web")
public class LayoutModelAttributes {

    @ModelAttribute("currentYear")
    public int currentYear() {
        return Year.now().getValue();
    }
}
