package com.elastiflix.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void reportsDomainFailuresAsAProblemDetailCarryingTheErrorCode() {
        ProblemDetail problem = handler.handleElastiflixException(
                new InferenceEndpointMissingException("elser", null));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(problem.getDetail()).contains("elser");
        assertThat(problem.getProperties()).containsEntry("errorCode", "INFERENCE_ENDPOINT_MISSING");
    }

    @Test
    void hidesTheDetailOfAnUnexpectedFailure() throws Exception {
        ProblemDetail problem = handler.handleGenericError(new IllegalStateException("index corrupt on node-3"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail())
                .isEqualTo("An unexpected error occurred.")
                .doesNotContain("node-3");
    }

    @Test
    void rethrowsFrameworkExceptionsSoTheyKeepTheirOwnStatus() {
        ErrorResponseException badRequest = new ErrorResponseException(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> handler.handleGenericError(badRequest)).isSameAs(badRequest);
    }

    @Test
    void exposesTheEndpointNameOnTheInferenceException() {
        assertThat(new InferenceEndpointMissingException("e5", null).endpointName()).isEqualTo("e5");
    }
}
