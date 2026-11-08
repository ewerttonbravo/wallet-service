package com.ebravo.wallet.adapter.web;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.ebravo.wallet.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class CorrelationIdApiTest {

    private static final String HEADER = "X-Correlation-Id";

    @Autowired
    private MockMvcTester mvc;

    // A fresh owner has no wallet: the request reaches the controller and answers 404, not 400.
    @Test
    void echoesTheCorrelationIdTheCallerSent() {
        assertThat(mvc.get().uri("/wallets/me").with(aToken()).header(HEADER, "gateway-7f3a:req.42_x"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasHeader(HEADER, "gateway-7f3a:req.42_x");
    }

    @Test
    void generatesACorrelationIdWhenTheCallerSendsNone() {
        var result = mvc.get().uri("/wallets/me").with(aToken()).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(UUID.fromString(result.getResponse().getHeader(HEADER))).isNotNull();
    }

    @Test
    void echoesItEvenWhenAuthenticationFails() {
        assertThat(mvc.get().uri("/wallets/me").header(HEADER, "req-401"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasHeader(HEADER, "req-401");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "a/b", "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-a"})
    void rejectsAMalformedCorrelationIdWithoutEchoingIt(String value) {
        var result = mvc.get().uri("/wallets/me").with(aToken()).header(HEADER, value).exchange();

        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .doesNotContainHeader(HEADER)
                .bodyJson().isStrictlyEqualTo("""
                        {"type": "about:blank", "title": "Bad Request", "status": 400,
                         "detail": "X-Correlation-Id must be 1 to 64 letters, digits, '.', '_', ':' or '-'"}
                        """);
    }

    private static RequestPostProcessor aToken() {
        return jwt().jwt(token -> token.subject(anOwner().value()));
    }
}
