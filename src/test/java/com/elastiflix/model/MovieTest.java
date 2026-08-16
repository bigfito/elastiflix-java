package com.elastiflix.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the two derived accessors that exist so the templates do not have to compute anything.
 * Both are defensive on purpose: the application reads an index it never writes, so it cannot
 * assume a document's fields are well formed.
 */
class MovieTest {

    private static Movie movieWith(String releaseDate) {
        Movie movie = new Movie();
        movie.setReleaseDate(releaseDate);
        return movie;
    }

    @Test
    void takesTheYearFromAFullReleaseDate() {
        assertThat(movieWith("2008-07-16").getReleaseYear()).isEqualTo("2008");
    }

    @Test
    void acceptsAYearOnlyReleaseDate() {
        // Elasticsearch's `date` type accepts a bare yyyy, so documents really do look like this.
        assertThat(movieWith("2008").getReleaseYear()).isEqualTo("2008");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "20", "200"})
    void reportsNoYearRatherThanThrowingOnAShortOrAbsentDate(String releaseDate) {
        // The templates used to call #strings.substring(releaseDate, 0, 4) here, which threw
        // StringIndexOutOfBoundsException and took down the whole results page, not just one card.
        assertThat(movieWith(releaseDate).getReleaseYear()).isNull();
    }

    @Test
    void limitsTheCastSummaryToFiveNames() {
        Movie movie = new Movie();
        movie.setCast(List.of("Christian Bale", "Heath Ledger", "Aaron Eckhart",
                "Michael Caine", "Maggie Gyllenhaal", "Gary Oldman"));

        assertThat(movie.getTopCast()).containsExactly("Christian Bale", "Heath Ledger",
                "Aaron Eckhart", "Michael Caine", "Maggie Gyllenhaal");
    }

    @Test
    void returnsTheWholeCastWhenItIsShorterThanTheLimit() {
        Movie movie = new Movie();
        movie.setCast(List.of("Tom Hanks", "Robin Wright"));

        assertThat(movie.getTopCast()).containsExactly("Tom Hanks", "Robin Wright");
    }

    @Test
    void returnsAnEmptyCastRatherThanNull() {
        assertThat(new Movie().getTopCast()).isEmpty();
    }
}
