package com.elastiflix.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes an {@code elasticsearch} component to {@code /actuator/health},
 * reporting DOWN when the cluster cannot be reached or the movie index is absent.
 *
 * <p>Hand-written rather than relying on Spring Boot's built-in Elasticsearch health
 * contributor, which activates on a {@code RestClient} bean — this application owns
 * its transport instead, and the index's existence matters as much as the ping.
 *
 * <p>The cluster host is deliberately <em>not</em> reported. It names an internal endpoint,
 * it tells an operator nothing the startup log does not already say, and the application has
 * no authentication — so anything published here is one HTTP request away from anyone.
 */
@Component
public class ElasticsearchHealthIndicator implements HealthIndicator {

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public ElasticsearchHealthIndicator(ElasticsearchClient client, ElasticsearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            if (!client.ping().value()) {
                return Health.down()
                        .withDetail("reason", "cluster did not answer a ping")
                        .build();
            }

            boolean indexExists = client.indices()
                    .exists(request -> request.index(properties.index()))
                    .value();

            return (indexExists ? Health.up() : Health.down())
                    .withDetail("index", properties.index())
                    .withDetail("indexExists", indexExists)
                    .build();
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("index", properties.index())
                    .build();
        }
    }
}
