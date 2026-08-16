package com.elastiflix;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.elastiflix.config.AppProperties;
import com.elastiflix.config.ElasticsearchHealthIndicator;
import com.elastiflix.config.ElasticsearchProperties;
import com.elastiflix.config.SecurityHeadersFilter;
import com.elastiflix.repository.MovieRepository;
import com.elastiflix.service.MovieService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application context.
 *
 * <p>The {@code @WebMvcTest} slices elsewhere only wire one controller each, so
 * nothing else would catch a bean that fails to construct, a duplicate health
 * contributor, or configuration properties that no longer bind. No cluster is
 * needed: the Elasticsearch client connects lazily, and the startup check reports a
 * failure without preventing the context from starting — which is itself part of
 * what this test asserts.
 */
@SpringBootTest
class ElastiflixApplicationTest {

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private MovieService movieService;

    @Autowired
    private SecurityHeadersFilter securityHeadersFilter;

    @Autowired
    private ElasticsearchHealthIndicator healthIndicator;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private ElasticsearchProperties elasticsearchProperties;

    @Test
    void contextLoadsWithEveryBeanWired() {
        assertThat(elasticsearchClient).isNotNull();
        assertThat(movieRepository).isNotNull();
        assertThat(movieService).isNotNull();
        assertThat(securityHeadersFilter).isNotNull();
        assertThat(healthIndicator).isNotNull();
    }

    @Test
    void bindsBothConfigurationPropertyGroups() {
        assertThat(appProperties.pageSize()).isPositive();
        assertThat(appProperties.tmdbImageBase()).startsWith("https://");
        assertThat(appProperties.tmdbImageBaseLarge()).startsWith("https://");

        assertThat(elasticsearchProperties.index()).isEqualTo("elastiflix-movies");
        assertThat(elasticsearchProperties.sslVerify()).isTrue();
    }

    @Test
    void bindsTheConfiguredTimeouts() {
        assertThat(elasticsearchProperties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(elasticsearchProperties.socketTimeout()).isEqualTo(Duration.ofSeconds(30));
    }
}
