package com.elastiflix.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

@Configuration
public class ElasticsearchConfig {

    @Value("${elasticsearch.host}")
    private String host;

    @Value("${elasticsearch.api-key}")
    private String apiKey;

    @Value("${elasticsearch.ssl-verify}")
    private boolean sslVerify;

    @Bean
    public ElasticsearchClient elasticsearchClient() throws Exception {
        var uri = java.net.URI.create(host);
        var httpHost = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());

        var restClientBuilder = RestClient.builder(httpHost)
                .setDefaultHeaders(new org.apache.http.Header[]{
                        new BasicHeader("Authorization", "ApiKey " + apiKey)
                });

        if (!sslVerify) {
            SSLContext sslContext = buildTrustAllSslContext();
            restClientBuilder.setHttpClientConfigCallback(builder ->
                    builder.setSSLContext(sslContext)
                           .setSSLHostnameVerifier((hostname, session) -> true)
            );
        }

        RestClient restClient = restClientBuilder.build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }

    private SSLContext buildTrustAllSslContext() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new java.security.SecureRandom());
        return ctx;
    }
}
