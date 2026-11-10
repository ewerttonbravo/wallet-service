package com.ebravo.wallet;

import static com.ebravo.wallet.Fixtures.anOwner;
import static com.ebravo.wallet.LocalIssuer.AUDIENCE;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * The actuator answers on a port of its own, which the platform alone reaches, so it needs no token
 * there; the application's port serves none of it but the probes. Run on real servers, since the
 * management port is a second one.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ContextConfiguration(initializers = LocalIssuerInitializer.class)
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
class ManagementServerTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private LocalIssuer issuer;

    @Test
    void servesMetricsWithoutATokenOnTheManagementPort() throws Exception {
        // Request metrics exist once the application's port has answered a request.
        assertThat(get(port, "/livez", null).statusCode()).isEqualTo(200);

        var scrape = get(managementPort, "/actuator/prometheus", null);

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/plain"));
        assertThat(scrape.body()).contains("jvm_memory_used_bytes", "http_server_requests_seconds");
        // The service's own series, there from the start.
        assertThat(scrape.body()).contains(
                "wallet_postings_total{outcome=\"posted\",type=\"deposit\"}",
                "wallet_postings_total{outcome=\"replayed\",type=\"transfer\"}",
                "wallet_refusals_total{reason=\"insufficient-funds\"}",
                "wallet_refusals_total{reason=\"wallet-busy\"}");
    }

    @Test
    void servesHealthWithoutATokenOnTheManagementPort() throws Exception {
        var health = get(managementPort, "/actuator/health", null);

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    /** Not even to a valid token: a caller on the application's port never reaches the actuator. */
    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/health", "/actuator"})
    void servesNoActuatorOnTheApplicationPort(String path) throws Exception {
        var token = issuer.tokenFor(anOwner(), AUDIENCE);

        assertThat(get(port, path, token).statusCode()).isEqualTo(404);
        assertThat(get(port, path, null).statusCode()).isEqualTo(401);
    }

    private static HttpResponse<String> get(int port, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
