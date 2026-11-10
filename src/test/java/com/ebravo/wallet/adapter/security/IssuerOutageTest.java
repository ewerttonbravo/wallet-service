package com.ebravo.wallet.adapter.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.ebravo.wallet.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * The issuer's keys cannot be read, so no token can be checked. Run on a real server: the failure
 * goes through the container's error dispatch, which MockMvc does not make.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/local",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/local/jwks"})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class IssuerOutageTest {

    @LocalServerPort
    private int port;

    @Test
    void doesNotBlameTheTokenWhenTheIssuerCannotBeReached(CapturedOutput output) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/wallets/me"))
                .header("Authorization", "Bearer " + aSignedLookingToken())
                .build();

        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        // The access log sees the failure thrown through it, before the error page answers it.
        var correlationId = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(output.getOut().lines().filter(line -> line.contains(correlationId)))
                .anyMatch(line -> line.contains("\"message\":\"GET /wallets/me 500\""));
    }

    /** Well-formed, so the decoder reaches for the issuer's keys to check the signature. */
    private static String aSignedLookingToken() {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        var header = encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"k\"}".getBytes(UTF_8));
        var claims = encoder.encodeToString("{\"sub\":\"someone\",\"exp\":4102444800}".getBytes(UTF_8));
        return header + "." + claims + "." + encoder.encodeToString("signature".getBytes(UTF_8));
    }
}
