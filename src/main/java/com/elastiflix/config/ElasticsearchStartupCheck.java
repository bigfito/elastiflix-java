package com.elastiflix.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Verifies once, at startup, that the cluster answers and that the movie index exists.
 *
 * <p>The REST client is lazy, so without this a wrong host, an expired API key or a
 * missing index only shows up when the first user runs a search — as a generic
 * "search unavailable" page, with the real cause buried in the logs.
 *
 * <p>Deliberately does <em>not</em> fail startup: the application degrades gracefully
 * when Elasticsearch is unavailable, and refusing to boot would make that pointless.
 * The check reports and steps aside.
 */
@Component
public class ElasticsearchStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchStartupCheck.class);

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public ElasticsearchStartupCheck(ElasticsearchClient client, ElasticsearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String host = properties.host();
        String index = properties.index();

        try {
            if (!client.ping().value()) {
                log.error("Elasticsearch at {} did not answer a ping. Check ELASTIC_HOST and ELASTIC_APIKEY — " +
                        "every search will fail until the cluster is reachable.", host);
                return;
            }

            if (client.indices().exists(request -> request.index(index)).value()) {
                log.info("Elasticsearch is reachable and index '{}' exists.", index);
            } else {
                log.error("Connected to Elasticsearch at {}, but index '{}' does not exist. " +
                        "Populate it with the Elastiflix loader before searching.", host, index);
            }
        } catch (Exception e) {
            // Broad on purpose: a diagnostic must never be the reason the app fails to boot.
            log.error("Could not verify the Elasticsearch connection at {} (index={}): {}. " +
                    "Check ELASTIC_HOST and ELASTIC_APIKEY — every search will fail until this is resolved.",
                    host, index, e.getMessage());
        }
    }
}
