package com.elastiflix.exception;

import com.elastiflix.controller.api.MovieApiController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders errors as JSON ({@link ProblemDetail}, RFC 7807) for {@link MovieApiController}.
 *
 * <p>Kept separate from {@link GlobalExceptionHandler} so REST API clients always receive a
 * JSON body instead of the Thymeleaf {@code error} view used by the web controllers.
 */
// Ordered ahead of GlobalExceptionHandler so this advice — not the HTML one — handles
// MovieApiController; without an explicit order, precedence would depend on bean registration order.
@Order(0)
@RestControllerAdvice(assignableTypes = MovieApiController.class)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ElastiflixException.class)
    public ProblemDetail handleElastiflixException(ElastiflixException ex) {
        log.error("[{}] {}", ex.errorCode(), ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setProperty("errorCode", ex.errorCode());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericError(Exception ex) throws Exception {
        // Framework exceptions (binding failure → 400, ...) already carry the right
        // status; rethrow so Spring's default handling applies instead of a blanket 500.
        if (ex instanceof ErrorResponse) {
            throw ex;
        }
        log.error("Unhandled exception reached the API layer", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    }
}
