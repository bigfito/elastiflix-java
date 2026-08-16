package com.elastiflix.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchModeTest {

    @ParameterizedTest
    @CsvSource({
            "title, TITLE",
            "BM25, BM25",
            "elser, ELSER",
            "e5, E5",
            "Hybrid, HYBRID",
            "elser_jina, ELSER_JINA",
            "ELSER_JINA, ELSER_JINA"
    })
    void parsesKnownModesCaseInsensitively(String input, SearchMode expected) {
        assertThat(SearchMode.fromString(input)).isEqualTo(expected);
    }

    @Test
    void onlyRetrieverBasedModesReportUsingARetriever() {
        // These are the modes Elasticsearch will not let us sort alongside.
        assertThat(SearchMode.HYBRID.usesRetriever()).isTrue();
        assertThat(SearchMode.ELSER_JINA.usesRetriever()).isTrue();
        assertThat(SearchMode.TITLE.usesRetriever()).isFalse();
        assertThat(SearchMode.BM25.usesRetriever()).isFalse();
        assertThat(SearchMode.ELSER.usesRetriever()).isFalse();
        assertThat(SearchMode.E5.usesRetriever()).isFalse();
    }

    @Test
    void onlyTheRerankedModeReportsBeingReranked() {
        assertThat(SearchMode.ELSER_JINA.isReranked()).isTrue();
        assertThat(SearchMode.HYBRID.isReranked()).isFalse();
        assertThat(SearchMode.ELSER.isReranked()).isFalse();
    }

    @Test
    void fallsBackToTitleForNullOrUnknownValues() {
        assertThat(SearchMode.fromString(null)).isEqualTo(SearchMode.TITLE);
        assertThat(SearchMode.fromString("not-a-mode")).isEqualTo(SearchMode.TITLE);
    }

    @Test
    void everyModeHasANonBlankLabel() {
        for (SearchMode mode : SearchMode.values()) {
            assertThat(mode.label()).isNotBlank();
        }
    }
}
