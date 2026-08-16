package com.elastiflix.controller.api;

import com.elastiflix.controller.MovieSearchParams;
import com.elastiflix.model.Movie;
import com.elastiflix.model.MovieSearchPage;
import com.elastiflix.service.MovieService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON API for movie search and lookup, mirroring the web UI's search behaviour
 * at {@code /api/search} and {@code /api/movies/{id}}.
 */
@RestController
@RequestMapping("/api")
public class MovieApiController {

    private final MovieService movieService;

    public MovieApiController(MovieService movieService) {
        this.movieService = movieService;
    }

    /**
     * Searches movies. Requires a non-blank {@code q}; all failures surface as a JSON
     * {@link ProblemDetail} — service errors via {@code ApiExceptionHandler}, a blank
     * {@code q} directly here.
     */
    @GetMapping("/search")
    public ResponseEntity<Object> search(@ModelAttribute MovieSearchParams params) {
        if (params.getQ() == null || params.getQ().isBlank()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST, "Query parameter 'q' must not be blank.");
            return ResponseEntity.badRequest().body(problem);
        }
        MovieSearchPage response = movieService.search(
                params.getQ(), params.getMode(), params.getPage(), params.getSize(), params.toFilters(), params.getSort());
        return ResponseEntity.ok(response);
    }

    /** Looks up a single movie by id, returning 404 if it does not exist. */
    @GetMapping("/movies/{id}")
    public ResponseEntity<Movie> getMovie(@PathVariable String id) {
        return movieService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
