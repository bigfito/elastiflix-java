package com.elastiflix.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Renders errors as the Thymeleaf {@code error} view for the web (Thymeleaf) controllers.
 *
 * <p>Scoped implicitly to every controller except {@link com.elastiflix.controller.api.MovieApiController},
 * which is handled instead by {@link ApiExceptionHandler} so REST clients keep getting JSON
 * instead of an HTML error page.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ElastiflixException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public String handleElastiflixException(ElastiflixException ex, Model model) {
        log.error("[{}] {}", ex.errorCode(), ex.getMessage(), ex);
        model.addAttribute("errorTitle", "Search Unavailable");
        // ElastiflixException messages are authored to be user-facing, unlike raw exception messages.
        model.addAttribute("errorMessage", ex.getMessage());
        return "error";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleGenericError(Exception ex, Model model) throws Exception {
        // Framework exceptions (missing static resource → 404, binding failure → 400, ...)
        // already carry the right status; rethrow so Spring's default handling applies
        // instead of masking them as a 500 and logging bot probes at ERROR level.
        if (ex instanceof ErrorResponse) {
            throw ex;
        }
        log.error("Unhandled exception reached the web layer", ex);
        model.addAttribute("errorTitle", "Something went wrong");
        model.addAttribute("errorMessage", "An unexpected error occurred. Please try again later.");
        return "error";
    }
}
