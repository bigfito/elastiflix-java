package com.elastiflix.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Movie {

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
}
