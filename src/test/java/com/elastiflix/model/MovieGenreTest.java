package com.elastiflix.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MovieGenreTest {

    @ParameterizedTest
    @CsvSource({
            "action, Action",
            "ACTION, Action",
            "'  Action  ', Action",
            "science fiction, Science Fiction",
            "tv movie, TV Movie"
    })
    void canonicalizesKnownGenresToTheCasingUsedInTheIndex(String input, String expected) {
        assertThat(MovieGenre.canonicalize(input)).isEqualTo(expected);
    }

    @Test
    void passesUnknownGenresThroughTrimmedRatherThanDroppingThem() {
        // Dropping the term would widen the search instead of narrowing it.
        assertThat(MovieGenre.canonicalize("  Film Noir ")).isEqualTo("Film Noir");
    }

    @Test
    void treatsNullAndBlankAsNoGenre() {
        assertThat(MovieGenre.canonicalize(null)).isNull();
        assertThat(MovieGenre.canonicalize("   ")).isNull();
    }

    @Test
    void exposesEveryGenreForThePicker() {
        assertThat(MovieGenre.displayNames())
                .hasSize(MovieGenre.values().length)
                .contains("Action", "Science Fiction", "Western")
                .doesNotHaveDuplicates();
    }

    @Test
    void displayNameMatchesTheCanonicalForm() {
        assertThat(MovieGenre.SCIENCE_FICTION.displayName()).isEqualTo("Science Fiction");
    }
}
