package com.elastiflix.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page-size rule lives on the properties record because two callers need the same answer:
 * the service, which clamps before querying, and the search page, which renders the picker
 * before any search has run.
 */
class AppPropertiesTest {

    private static final int CONFIGURED_DEFAULT = 50;

    private final AppProperties properties = new AppProperties(CONFIGURED_DEFAULT,
            "https://image.tmdb.org/t/p/w500", "https://image.tmdb.org/t/p/w1280");

    @ParameterizedTest
    @ValueSource(ints = {1, 25, 50, 99, 100})
    void keepsASizeInsideTheAllowedRange(int requested) {
        assertThat(properties.effectivePageSize(requested)).isEqualTo(requested);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, 9_999})
    void fallsBackToTheConfiguredDefaultOutsideTheRange(int requested) {
        assertThat(properties.effectivePageSize(requested)).isEqualTo(CONFIGURED_DEFAULT);
    }

    @Test
    void treatsAnOmittedSizeAsTheDefault() {
        // MovieSearchParams leaves `size` at 0 when the request omits it, which is what makes
        // app.page-size the single authority for the default.
        assertThat(properties.effectivePageSize(0)).isEqualTo(CONFIGURED_DEFAULT);
    }
}
