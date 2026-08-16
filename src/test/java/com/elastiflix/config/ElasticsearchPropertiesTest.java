package com.elastiflix.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchPropertiesTest {

    private static ElasticsearchProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("elasticsearch", ElasticsearchProperties.class)
                .get();
    }

    @Test
    void appliesTimeoutDefaultsWhenThePropertiesAreAbsent() {
        // Without the @DefaultValue declarations these bind to null, and the config
        // then dies with an NPE on connectTimeout().toMillis() instead of failing fast.
        ElasticsearchProperties properties = bind(Map.of(
                "elasticsearch.host", "https://localhost:9200",
                "elasticsearch.api-key", "test-key",
                "elasticsearch.index", "elastiflix-movies"));

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.socketTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void verifiesTlsByDefaultWhenTheFlagIsAbsent() {
        ElasticsearchProperties properties = bind(Map.of(
                "elasticsearch.host", "https://localhost:9200",
                "elasticsearch.api-key", "test-key",
                "elasticsearch.index", "elastiflix-movies"));

        assertThat(properties.sslVerify()).isTrue();
    }

    @Test
    void honoursExplicitlyConfiguredValues() {
        ElasticsearchProperties properties = bind(Map.of(
                "elasticsearch.host", "https://es.example.com:9243",
                "elasticsearch.api-key", "test-key",
                "elasticsearch.index", "movies",
                "elasticsearch.ssl-verify", "false",
                "elasticsearch.connect-timeout", "12s",
                "elasticsearch.socket-timeout", "1m"));

        assertThat(properties.sslVerify()).isFalse();
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(12));
        assertThat(properties.socketTimeout()).isEqualTo(Duration.ofMinutes(1));
    }
}
