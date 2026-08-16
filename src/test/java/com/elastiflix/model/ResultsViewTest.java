package com.elastiflix.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ResultsViewTest {

    @ParameterizedTest
    @CsvSource({
            "grid, GRID",
            "GRID, GRID",
            "list, LIST",
            "' List ', LIST"
    })
    void parsesKnownViewsCaseInsensitively(String input, ResultsView expected) {
        assertThat(ResultsView.fromString(input)).isEqualTo(expected);
    }

    @Test
    void fallsBackToGridForNullBlankOrUnknownValues() {
        assertThat(ResultsView.fromString(null)).isEqualTo(ResultsView.GRID);
        assertThat(ResultsView.fromString("")).isEqualTo(ResultsView.GRID);
        assertThat(ResultsView.fromString("not-a-view")).isEqualTo(ResultsView.GRID);
        assertThat(ResultsView.defaultView()).isEqualTo(ResultsView.GRID);
    }

    @Test
    void exposesTheLowercaseFormTheTemplatesCompareAgainst() {
        assertThat(ResultsView.GRID.value()).isEqualTo("grid");
        assertThat(ResultsView.LIST.value()).isEqualTo("list");
    }
}
