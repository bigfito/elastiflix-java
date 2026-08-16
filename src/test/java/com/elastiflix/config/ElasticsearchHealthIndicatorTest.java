package com.elastiflix.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElasticsearchHealthIndicatorTest {

    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);

    private final ElasticsearchHealthIndicator indicator = new ElasticsearchHealthIndicator(
            client,
            new ElasticsearchProperties("https://localhost:9200", "key", "elastiflix-movies", true,
                    Duration.ofSeconds(5), Duration.ofSeconds(30)));

    @BeforeEach
    void wireIndicesClient() {
        when(client.indices()).thenReturn(indices);
    }

    @SuppressWarnings("unchecked")
    private void stubIndexExists(boolean exists) throws IOException {
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(exists));
    }

    @Test
    void isUpWhenTheClusterAnswersAndTheIndexExists() throws IOException {
        when(client.ping()).thenReturn(new BooleanResponse(true));
        stubIndexExists(true);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("index", "elastiflix-movies")
                .containsEntry("indexExists", true);
    }

    @Test
    void isDownWhenTheIndexIsMissingEvenThoughTheClusterAnswers() throws IOException {
        when(client.ping()).thenReturn(new BooleanResponse(true));
        stubIndexExists(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("indexExists", false);
    }

    @Test
    void isDownWhenTheClusterDoesNotAnswerAPing() throws IOException {
        when(client.ping()).thenReturn(new BooleanResponse(false));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "cluster did not answer a ping");
    }

    @Test
    void isDownRatherThanThrowingWhenTheClusterIsUnreachable() throws IOException {
        when(client.ping()).thenThrow(new IOException("connection refused"));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
