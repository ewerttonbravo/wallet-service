package com.ebravo.wallet;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The issuer refuses connections, so its keys cannot be read and no token can be checked. The
 * service is not down: only requests that carry a token fail, and they are told to retry.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/local",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/local/jwks"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ApiContractValidation.class})
@ExtendWith(OutputCaptureExtension.class)
public class IssuerOutageTest {

    private static final String DETAIL = "The token cannot be checked now, retry the request";

    @Autowired
    private MockMvcTester mvc;

    /** Not a bad token: no challenge, and the same 503 an unavailable database gets. */
    @Test
    void answersServiceUnavailableWhenTheKeysCannotBeRead() {
        var response = mvc.get().uri("/wallets/me").header(HttpHeaders.AUTHORIZATION, bearer()).exchange();

        assertThat(response)
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasHeader(HttpHeaders.RETRY_AFTER, "1")
                .doesNotContainHeader(HttpHeaders.WWW_AUTHENTICATE)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.detail").isEqualTo(DETAIL);
    }

    /** Answered by the service, the failure no longer reaches the access line: the log must tell why. */
    @Test
    void logsTheFailureWithTheCorrelationId(CapturedOutput output) {
        var response = mvc.get().uri("/wallets/me").header(HttpHeaders.AUTHORIZATION, bearer()).exchange();

        var correlationId = response.getResponse().getHeader("X-Correlation-Id");
        assertThat(correlationId).isNotNull();
        assertThat(output.getOut().lines().filter(line -> line.contains(correlationId)))
                .anyMatch(line -> line.contains("\"level\":\"ERROR\"")
                        && line.contains("The token could not be checked")
                        && line.contains("org.springframework.security.authentication.AuthenticationServiceException"));
    }

    /**
     * A token sent to a public path is still checked, so it cannot be either. The guard: the same
     * path without a token answers, so the service and its readiness do not follow the issuer.
     */
    @Test
    void aPublicPathWithATokenIsUnavailableToo() {
        assertThat(mvc.get().uri("/livez").header(HttpHeaders.AUTHORIZATION, bearer()))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyJson().extractingPath("$.detail").isEqualTo(DETAIL);
        assertThat(mvc.get().uri("/openapi.yaml").header(HttpHeaders.AUTHORIZATION, bearer()))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(mvc.get().uri("/livez")).hasStatusOk();
        assertThat(mvc.get().uri("/readyz")).hasStatusOk();
    }

    /** Well-formed, so the decoder reaches for the issuer's keys to check the signature. */
    public static String bearer() {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        var header = encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"k\"}".getBytes(UTF_8));
        var claims = encoder.encodeToString("{\"sub\":\"someone\",\"exp\":4102444800}".getBytes(UTF_8));
        return "Bearer " + header + "." + claims + "." + encoder.encodeToString("signature".getBytes(UTF_8));
    }
}
