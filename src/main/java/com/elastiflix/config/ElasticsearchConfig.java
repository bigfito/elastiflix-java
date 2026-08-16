package com.elastiflix.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import com.elastiflix.exception.ElasticsearchClientInitializationException;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;

/**
 * Builds the {@link ElasticsearchClient} bean used to talk to the movie index.
 */
@Configuration
public class ElasticsearchConfig {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchConfig.class);

    /** Hostname suffixes reserved by convention for local or private networks. */
    private static final List<String> LOCAL_HOST_SUFFIXES =
            List.of(".local", ".localhost", ".localdomain", ".internal", ".test");

    private final ElasticsearchProperties properties;

    public ElasticsearchConfig(ElasticsearchProperties properties) {
        this.properties = properties;
    }

    /**
     * Creates the transport (low-level REST client + JSON mapper), wiring the API key
     * header and, when {@code elasticsearch.ssl-verify=false}, a trust-all TLS context
     * intended strictly for local development clusters with self-signed certificates.
     *
     * <p>Exposed as its own bean so Spring closes it (and its connection pool/threads)
     * on shutdown — {@link ElasticsearchTransport} is {@code Closeable}.
     *
     * @throws ElasticsearchClientInitializationException if {@code elasticsearch.host} is
     *         unusable, or if TLS verification was disabled for a non-local HTTPS cluster
     */
    @Bean
    public ElasticsearchTransport elasticsearchTransport() {
        HttpHost httpHost = toHttpHost(properties.host());

        Rest5ClientBuilder restClientBuilder = Rest5Client.builder(httpHost)
                .setDefaultHeaders(new Header[]{
                        new BasicHeader("Authorization", "ApiKey " + properties.apiKey())
                })
                .setRequestConfigCallback(builder -> builder
                        .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(properties.socketTimeout().toMillis())))
                .setConnectionConfigCallback(builder -> builder
                        .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
                        .setSocketTimeout(Timeout.ofMilliseconds(properties.socketTimeout().toMillis())));

        if (!properties.sslVerify()) {
            applyDisabledTlsVerification(restClientBuilder, httpHost);
        }

        log.info("Connecting to Elasticsearch at {} (index={}, ssl-verify={})",
                properties.host(), properties.index(), properties.sslVerify());
        return new Rest5ClientTransport(restClientBuilder.build(), new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }

    private void applyDisabledTlsVerification(Rest5ClientBuilder restClientBuilder, HttpHost httpHost) {
        if (!"https".equalsIgnoreCase(httpHost.getSchemeName())) {
            // Plain HTTP never performs a handshake, so a trust-all context would be
            // dead weight — but the operator probably did not mean to run unencrypted.
            log.warn("elasticsearch.ssl-verify is disabled and {} is plain HTTP: traffic to the cluster " +
                    "(including the API key) is unencrypted.", properties.host());
            return;
        }

        requireLocalTarget(httpHost.getHostName());
        log.warn("elasticsearch.ssl-verify is disabled: TLS certificate and hostname validation " +
                        "will be skipped for all requests to {}. This must never be used against a production cluster.",
                properties.host());

        SSLContext trustAllContext = buildTrustAllSslContext();
        restClientBuilder.setConnectionManagerCallback(cmBuilder ->
                cmBuilder.setTlsStrategy(ClientTlsStrategyBuilder.create()
                        .setSslContext(trustAllContext)
                        .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                        .build())
        );
    }

    /**
     * Parses {@code elasticsearch.host} into an {@link HttpHost}, rejecting the shapes
     * the low-level REST client would otherwise discard without a word — notably a path
     * prefix (every request would silently go to the wrong URL) and embedded credentials.
     */
    static HttpHost toHttpHost(String host) {
        String trimmed = host == null ? "" : host.trim();

        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            throw invalidHost(trimmed, "it contains an unresolved placeholder — please set the ELASTIC_HOST environment variable", null);
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw invalidHost(trimmed, "it is not a valid URL", e);
        }

        if (uri.getScheme() == null || uri.getHost() == null) {
            throw invalidHost(trimmed, "it must be an absolute URL including the scheme, e.g. https://my-cluster:9243", null);
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw invalidHost(trimmed, "the scheme must be http or https but was '" + scheme + "'", null);
        }
        if (uri.getUserInfo() != null) {
            throw invalidHost(trimmed, "it must not embed credentials — use elasticsearch.api-key instead", null);
        }

        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw invalidHost(trimmed, "it must not contain a path but was '" + path
                    + "'; the Elasticsearch client appends its own paths and would drop this prefix", null);
        }

        return new HttpHost(scheme, uri.getHost(), uri.getPort());
    }

    /**
     * Fails startup when TLS verification is switched off for a cluster that is not
     * demonstrably local — otherwise one environment variable is all it takes to expose
     * the API key to anyone able to intercept the connection.
     */
    static void requireLocalTarget(String hostName) {
        if (isLocalTarget(hostName)) {
            return;
        }
        throw new ElasticsearchClientInitializationException(
                "elasticsearch.ssl-verify=false switches off TLS certificate and hostname validation entirely, "
                        + "which is only acceptable against a local development cluster — but '" + hostName
                        + "' is not one. Set ELASTIC_SSL_VERIFY=true (or unset it) when connecting to a remote cluster.",
                null);
    }

    /** Recognises loopback, RFC-1918, single-label and reserved-suffix hosts without doing any DNS lookup. */
    static boolean isLocalTarget(String hostName) {
        if (hostName == null || hostName.isBlank()) {
            return false;
        }

        String host = hostName.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        if ("localhost".equals(host) || "::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host)) {
            return true;
        }
        for (String suffix : LOCAL_HOST_SUFFIXES) {
            if (host.endsWith(suffix)) {
                return true;
            }
        }
        // A single-label name — a docker-compose service, a Kubernetes short name —
        // has no public TLD and so cannot be reached from outside the local network.
        if (!host.contains(".")) {
            return true;
        }
        return isPrivateIpv4(host);
    }

    private static boolean isPrivateIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }

        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }

        return octets[0] == 127                                            // loopback
                || octets[0] == 10                                         // 10.0.0.0/8
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) // 172.16.0.0/12
                || (octets[0] == 192 && octets[1] == 168)                  // 192.168.0.0/16
                || (octets[0] == 169 && octets[1] == 254);                 // link-local
    }

    private static ElasticsearchClientInitializationException invalidHost(String host, String reason, Throwable cause) {
        return new ElasticsearchClientInitializationException(
                "elasticsearch.host is invalid ('" + host + "'): " + reason + ".", cause);
    }

    private SSLContext buildTrustAllSslContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            return ctx;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new ElasticsearchClientInitializationException(
                    "Could not build the trust-all TLS context for elasticsearch.ssl-verify=false", e);
        }
    }
}
