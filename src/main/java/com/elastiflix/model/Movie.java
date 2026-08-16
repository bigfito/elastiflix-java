package com.elastiflix.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Movie document as stored in the {@code elastiflix-movies} index.
 *
 * <p>Snake_case fields are mapped with per-field {@link JsonProperty} (from
 * {@code jackson-annotations}) rather than a class-level {@code @JsonNaming}
 * strategy on purpose: {@code @JsonNaming} lives in Jackson 2's databind and is
 * ignored by the Jackson 3 mapper Spring Boot 4 uses for HTTP responses, while
 * {@code @JsonProperty} is honored by both it and the Jackson 2 mapper the
 * Elasticsearch client uses.
 *
 * <p>Mutable (not a record) because {@link com.elastiflix.service.MovieService}
 * enriches {@code posterPath}/{@code backdropPath} into absolute URLs in place
 * after Jackson deserializes it from an Elasticsearch hit.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Movie {

    /** How many cast members {@link #getTopCast()} returns. */
    private static final int TOP_CAST_LIMIT = 5;

    private String id;

    private String title;

    @JsonProperty("original_title")
    private String originalTitle;

    @JsonProperty("original_language")
    private String originalLanguage;

    private String overview;

    private String plot;

    private String tagline;

    @JsonProperty("poster_path")
    private String posterPath;

    @JsonProperty("backdrop_path")
    private String backdropPath;

    private List<String> genres;

    private List<String> cast;

    @JsonProperty("release_date")
    private String releaseDate;

    @JsonProperty("vote_average")
    private Double voteAverage;

    @JsonProperty("vote_count")
    private Integer voteCount;

    private Double popularity;

    private Integer runtime;

    private String rating;

    private String status;

    private String homepage;

    @JsonProperty("imdb_id")
    private String imdbId;

    private Double budget;

    private Double revenue;

    @JsonProperty("production_companies")
    private List<String> productionCompanies;

    @JsonProperty("production_countries")
    private List<String> productionCountries;

    @JsonProperty("spoken_languages")
    private List<String> spokenLanguages;

    private Boolean adult;

    private Boolean video;

    /**
     * The four-digit release year, or {@code null} when {@code release_date} is absent or too
     * short to carry one.
     *
     * <p>The templates used to slice this out with {@code #strings.substring(releaseDate, 0, 4)},
     * which throws {@code StringIndexOutOfBoundsException} — and fails the whole page, not just
     * one card — for any document whose date is shorter than four characters. The app never
     * writes the index, so it cannot assume the value is well formed.
     *
     * <p>{@code @JsonIgnore} because this is a view convenience, not part of the document: it
     * must not appear in API responses, nor be written back by the tests that index fixtures.
     */
    @JsonIgnore
    public String getReleaseYear() {
        return releaseDate != null && releaseDate.length() >= 4 ? releaseDate.substring(0, 4) : null;
    }

    /**
     * The first few cast members, for the one-line summary on the list view.
     *
     * <p>Here rather than in the template so the bound is testable and the view stays free of
     * {@code T(java.lang.Math)} type references.
     */
    @JsonIgnore
    public List<String> getTopCast() {
        if (cast == null) {
            return List.of();
        }
        return cast.subList(0, Math.min(cast.size(), TOP_CAST_LIMIT));
    }
}
