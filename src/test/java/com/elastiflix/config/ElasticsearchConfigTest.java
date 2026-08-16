package com.elastiflix.config;

import co.elastic.clients.transport.ElasticsearchTransport;
import com.elastiflix.exception.ElasticsearchClientInitializationException;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticsearchConfigTest {

    private static ElasticsearchProperties properties(String host, boolean sslVerify) {
        return new ElasticsearchProperties(host, "test-key", "elastiflix-movies", sslVerify,
                Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    // ---------------------------------------------------------------- host parsing

    @Test
    void parsesSchemeHostAndPort() {
        HttpHost host = ElasticsearchConfig.toHttpHost("https://es.example.com:9243");

        assertThat(host.getSchemeName()).isEqualTo("https");
        assertThat(host.getHostName()).isEqualTo("es.example.com");
        assertThat(host.getPort()).isEqualTo(9243);
    }

    @Test
    void leavesThePortUnsetWhenTheUrlOmitsIt() {
        assertThat(ElasticsearchConfig.toHttpHost("https://es.example.com").getPort()).isEqualTo(-1);
    }

    @Test
    void toleratesSurroundingWhitespaceAndATrailingSlash() {
        HttpHost host = ElasticsearchConfig.toHttpHost("  https://es.example.com:9243/  ");

        assertThat(host.getHostName()).isEqualTo("es.example.com");
        assertThat(host.getPort()).isEqualTo(9243);
    }

    @Test
    void rejectsAHostWithAPathBecauseTheClientWouldSilentlyDropIt() {
        assertThatThrownBy(() -> ElasticsearchConfig.toHttpHost("https://proxy.internal/es-cluster"))
                .isInstanceOf(ElasticsearchClientInitializationException.class)
                .hasMessageContaining("must not contain a path")
                .hasMessageContaining("/es-cluster");
    }

    @Test
    void rejectsEmbeddedCredentials() {
        assertThatThrownBy(() -> ElasticsearchConfig.toHttpHost("https://user:secret@es.example.com:9243"))
                .isInstanceOf(ElasticsearchClientInitializationException.class)
                .hasMessageContaining("must not embed credentials");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://es.example.com", "file://es.example.com"})
    void rejectsANonHttpScheme(String host) {
        assertThatThrownBy(() -> ElasticsearchConfig.toHttpHost(host))
                .isInstanceOf(ElasticsearchClientInitializationException.class)
                .hasMessageContaining("scheme must be http or https");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "es.example.com:9243", "/relative/path"})
    void rejectsAnythingThatIsNotAnAbsoluteUrl(String host) {
        assertThatThrownBy(() -> ElasticsearchConfig.toHttpHost(host))
                .isInstanceOf(ElasticsearchClientInitializationException.class)
                .hasMessageContaining("absolute URL");
    }

    @Test
    void rejectsANullHostWithTheSameClearMessage() {
        assertThatThrownBy(() -> ElasticsearchConfig.toHttpHost(null))
                .isInstanceOf(ElasticsearchClientInitializationException.class)
                .hasMessageContaining("absolute URL");
    }

    @Test
    void rejectsAMalformedUrl() {
        assertThatThrownBy(() -> ElasticsearchConfig.toHttpHost("https://exa mple.com"))
                .isInstanceOf(ElasticsearchClientInitializationException.class)
                .hasMessageContaining("not a valid URL");
    }

    @Test
    void rejectsAnUnresolvedPlaceholder() {
        assertThatThrownBy(() -> ElasticsearchConfig.toHttpHost("${ELASTIC_HOST}"))
                .isInstanceOf(ElasticsearchClientInitializationException.class)
                .hasMessageContaining("unresolved placeholder")
                .hasMessageContaining("ELASTIC_HOST");
    }

    // ---------------------------------------------------------------- local target detection

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost", "127.0.0.1", "127.1.2.3", "::1", "[::1]", "0:0:0:0:0:0:0:1",
            "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.10", "169.254.10.1",
            "elasticsearch", "es-node-1",
            "cluster.local", "es.localhost", "vault.internal", "node.localdomain", "es.test"
    })
    void recognisesLocalAndPrivateTargets(String hostName) {
        assertThat(ElasticsearchConfig.isLocalTarget(hostName)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "es.example.com", "8.8.8.8", "1.1.1.1",
            "11.0.0.1", "172.32.0.1", "172.15.0.1", "192.169.1.1",
            "my-cluster.es.us-central1.gcp.elastic.cloud",
            "999.999.999.999"
    })
    void doesNotMistakePublicTargetsForLocalOnes(String hostName) {
        assertThat(ElasticsearchConfig.isLocalTarget(hostName)).isFalse();
    }

    @Test
    void treatsAnAbsentHostnameAsNonLocal() {
        assertThat(ElasticsearchConfig.isLocalTarget(null)).isFalse();
        assertThat(ElasticsearchConfig.isLocalTarget("  ")).isFalse();
    }

    // ---------------------------------------------------------------- transport wiring

    @Test
    void failsFastWhenTlsVerificationIsDisabledForARemoteCluster() {
        ElasticsearchConfig config = new ElasticsearchConfig(properties("https://es.example.com:9243", false));

        assertThatThrownBy(config::elasticsearchTransport)
                .isInstanceOf(ElasticsearchClientInitializationException.class)
                .hasMessageContaining("ssl-verify=false")
                .hasMessageContaining("es.example.com");
    }

    @Test
    void allowsTlsVerificationToBeDisabledForALocalCluster() throws Exception {
        ElasticsearchConfig config = new ElasticsearchConfig(properties("https://localhost:9200", false));

        try (ElasticsearchTransport transport = config.elasticsearchTransport()) {
            assertThat(transport).isNotNull();
        }
    }

    @Test
    void buildsTheTransportForARemoteClusterWhenVerificationIsOn() throws Exception {
        ElasticsearchConfig config = new ElasticsearchConfig(properties("https://es.example.com:9243", true));

        try (ElasticsearchTransport transport = config.elasticsearchTransport()) {
            assertThat(transport).isNotNull();
        }
    }

    @Test
    void skipsTlsHandlingEntirelyOverPlainHttp() throws Exception {
        // No handshake happens, so there is nothing for the trust-all context to relax.
        ElasticsearchConfig config = new ElasticsearchConfig(properties("http://elasticsearch:9200", false));

        try (ElasticsearchTransport transport = config.elasticsearchTransport()) {
            assertThat(transport).isNotNull();
        }
    }

    @Test
    void createsTheClientFromTheTransport() throws Exception {
        ElasticsearchConfig config = new ElasticsearchConfig(properties("https://localhost:9200", true));

        try (ElasticsearchTransport transport = config.elasticsearchTransport()) {
            assertThat(config.elasticsearchClient(transport)).isNotNull();
        }
    }
}
