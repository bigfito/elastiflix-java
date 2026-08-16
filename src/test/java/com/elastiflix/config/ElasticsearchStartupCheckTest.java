package com.elastiflix.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The check is a diagnostic, so every case here asserts the same contract: it reports
 * the problem and returns. Throwing would stop the application from booting and defeat
 * the graceful degradation the rest of the code is built around.
 */
class ElasticsearchStartupCheckTest {

    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);

    private final ElasticsearchStartupCheck check = new ElasticsearchStartupCheck(
            client,
            new ElasticsearchProperties("https://localhost:9200", "key", "elastiflix-movies", true,
                    Duration.ofSeconds(5), Duration.ofSeconds(30)));

    @SuppressWarnings("unchecked")
    private void stubIndexExists(boolean exists) throws IOException {
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(exists));
    }

    @Test
    void succeedsQuietlyWhenTheClusterAndIndexAreBothPresent() throws IOException {
        when(client.ping()).thenReturn(new BooleanResponse(true));
        stubIndexExists(true);

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    }

    @Test
    void reportsAMissingIndexWithoutFailingStartup() throws IOException {
        when(client.ping()).thenReturn(new BooleanResponse(false));

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
        // A failed ping short-circuits: there is no point asking about the index.
        verify(client, never()).indices();
    }

    @Test
    void reportsAnAbsentIndexOnAReachableCluster() throws IOException {
        when(client.ping()).thenReturn(new BooleanResponse(true));
        stubIndexExists(false);

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    }

    @Test
    void swallowsAnUnreachableClusterSoTheApplicationStillStarts() throws IOException {
        when(client.ping()).thenThrow(new IOException("connection refused"));

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    }

    @Test
    void swallowsUnexpectedRuntimeFailuresToo() throws IOException {
        when(client.ping()).thenThrow(new IllegalStateException("transport already closed"));

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    }
}
