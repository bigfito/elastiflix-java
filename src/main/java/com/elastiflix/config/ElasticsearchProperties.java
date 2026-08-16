package com.elastiflix.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Connection settings for the Elasticsearch cluster, bound immutably from the
 * {@code elasticsearch.*} prefix.
 *
 * <p>Validated at startup so a missing {@code ELASTIC_HOST}/{@code ELASTIC_APIKEY}
 * fails fast with a message naming the property, instead of an NPE on the first request.
 *
 * @param host           base URL of the Elasticsearch cluster, e.g. {@code https://my-cluster:9243};
 *                       scheme and host are required, a path is not allowed
 * @param apiKey         API key used for the {@code Authorization: ApiKey ...} header
 * @param index          name of the index holding movie documents
 * @param sslVerify      whether to validate the server's TLS certificate and hostname.
 *                       Defaults to {@code true}; {@code false} is rejected at startup for
 *                       anything but a local/private HTTPS target
 * @param connectTimeout socket connect timeout; defaults to 5s when unset
 * @param socketTimeout  socket read timeout; defaults to 30s when unset
 */
@Validated
@ConfigurationProperties(prefix = "elasticsearch")
public record ElasticsearchProperties(
        @NotBlank String host,
        @NotBlank String apiKey,
        @NotBlank String index,
        @DefaultValue("true") boolean sslVerify,
        // Defaults live here rather than only in application.yml so that dropping the
        // key — or overriding the config from outside — cannot produce a startup NPE.
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration socketTimeout
) {
}
