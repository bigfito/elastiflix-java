package com.elastiflix.model;

import org.junit.jupiter.api.Test;

import java.time.Year;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseYearTest {

    @Test
    void acceptsYearsInsideTheFilmEra() {
        assertThat(ReleaseYear.isPlausible(1994)).isTrue();
        assertThat(ReleaseYear.isPlausible(ReleaseYear.MIN_YEAR)).isTrue();
        assertThat(ReleaseYear.isPlausible(Year.now().getValue())).isTrue();
    }

    @Test
    void acceptsAnnouncedFilmsAFewYearsAhead() {
        assertThat(ReleaseYear.isPlausible(Year.now().getValue() + ReleaseYear.MAX_YEARS_AHEAD)).isTrue();
    }

    @Test
    void rejectsYearsBeforeCinemaExisted() {
        assertThat(ReleaseYear.isPlausible(1800)).isFalse();
        assertThat(ReleaseYear.isPlausible(ReleaseYear.MIN_YEAR - 1)).isFalse();
    }

    @Test
    void rejectsYearsTooFarInTheFuture() {
        assertThat(ReleaseYear.isPlausible(Year.now().getValue() + ReleaseYear.MAX_YEARS_AHEAD + 1)).isFalse();
        assertThat(ReleaseYear.isPlausible(9999)).isFalse();
    }

    @Test
    void treatsAnAbsentYearAsPlausibleBecauseItIsNotAFilterAtAll() {
        assertThat(ReleaseYear.isPlausible((Integer) null)).isTrue();
    }

    @Test
    void appliesTheSameRuleToABoxedYear() {
        assertThat(ReleaseYear.isPlausible(Integer.valueOf(1994))).isTrue();
        assertThat(ReleaseYear.isPlausible(Integer.valueOf(1200))).isFalse();
    }
}
