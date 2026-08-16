package com.elastiflix.controller;

import com.elastiflix.model.SearchMode;
import com.elastiflix.repository.MovieRepository;
import lombok.Data;

import java.util.List;

/**
 * Search query parameters shared by the web ({@code /search}) and REST
 * ({@code /api/search}) controllers, bound via {@code @ModelAttribute}.
 *
 * <p>Field initialisers below are the defaults applied when a request omits the
 * corresponding parameter — Spring's data binder only overwrites fields actually
 * present on the request. {@code size} is deliberately left at {@code 0}: the
 * service treats any out-of-range value as "use {@code app.page-size}", which
 * keeps the configured page size the single authority instead of duplicating the
 * number here.
 */
@Data
public class MovieSearchParams {

    private String q = "";
    private String mode = SearchMode.defaultMode().name();
    private int page = 1;
    private int size = 0;
    private List<String> genres;
    private Integer year;
    private String rating;
    private String sort;

    /** Builds the repository filter record from the genre/year/rating fields. */
    public MovieRepository.SearchFilters toFilters() {
        return new MovieRepository.SearchFilters(genres, year, rating);
    }
}
