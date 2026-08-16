package com.elastiflix.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.ErrorResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void showsTheDomainMessageBecauseItIsWrittenForEndUsers() {
        Model model = new ExtendedModelMap();

        String view = handler.handleElastiflixException(
                new SearchUnavailableException("Search is temporarily unavailable. Please try again later.", null), model);

        assertThat(view).isEqualTo("error");
        assertThat(model.getAttribute("errorTitle")).isEqualTo("Search Unavailable");
        assertThat(model.getAttribute("errorMessage"))
                .isEqualTo("Search is temporarily unavailable. Please try again later.");
    }

    @Test
    void hidesTheDetailOfAnUnexpectedFailure() throws Exception {
        Model model = new ExtendedModelMap();

        String view = handler.handleGenericError(new IllegalStateException("connection pool exhausted at 10.0.0.7"), model);

        assertThat(view).isEqualTo("error");
        assertThat(model.getAttribute("errorTitle")).isEqualTo("Something went wrong");
        assertThat(model.getAttribute("errorMessage")).asString()
                .isEqualTo("An unexpected error occurred. Please try again later.")
                .doesNotContain("10.0.0.7");
    }

    @Test
    void rethrowsFrameworkExceptionsSoTheyKeepTheirOwnStatus() {
        // A missing static resource is a 404 and a binding failure a 400; masking either
        // as a 500 would also log every bot probe at ERROR level.
        Model model = new ExtendedModelMap();
        ErrorResponseException notFound = new ErrorResponseException(HttpStatus.NOT_FOUND);

        assertThatThrownBy(() -> handler.handleGenericError(notFound, model)).isSameAs(notFound);
    }
}
