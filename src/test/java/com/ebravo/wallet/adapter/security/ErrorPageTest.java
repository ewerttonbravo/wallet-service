package com.ebravo.wallet.adapter.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebravo.wallet.IssuerOutageTest;
import com.ebravo.wallet.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * A failure while the token is checked, which the service did not decide, goes to the error page.
 * That page must answer it as the failure it is, not refuse it as an anonymous request: a valid
 * token would be told it was bad. Run on a real server: MockMvc makes no error dispatch.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/local",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/local/jwks",
        "management.server.port=0"})
@Import({TestcontainersConfiguration.class, ErrorPageTest.FailingDecoder.class})
@ExtendWith(OutputCaptureExtension.class)
class ErrorPageTest {

    @LocalServerPort
    private int port;

    @Test
    void doesNotBlameTheTokenForAFailureOfTheService(CapturedOutput output) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/wallets/me"))
                .header("Authorization", IssuerOutageTest.bearer())
                .build();

        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        // The access log sees the failure thrown through it, before the error page answers it, and
        // carries it: the container logs it again only after the request's context is gone.
        var correlationId = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(output.getOut().lines().filter(line -> line.contains(correlationId)))
                .anyMatch(line -> line.contains("\"message\":\"GET /wallets/me 500\"")
                        && line.contains("\"level\":\"ERROR\"")
                        && line.contains("\"type\":\"java.lang.IllegalStateException\""));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingDecoder {

        /** Not a {@code JwtException}: Spring Security passes it through untouched. */
        @Bean
        JwtDecoder failingDecoder() {
            return token -> {
                throw new IllegalStateException("The decoder failed");
            };
        }
    }
}
