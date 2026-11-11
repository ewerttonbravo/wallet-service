package com.ebravo.wallet.adapter.web;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.ebravo.wallet.IntegrationTest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class CorrelationIdApiTest {

    private static final String HEADER = "X-Correlation-Id";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JsonMapper json;

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
                        {"title": "Bad Request", "status": 400,
                         "detail": "X-Correlation-Id must be 1 to 64 letters, digits, '.', '_', ':' or '-'",
                         "instance": "/wallets/me"}
                        """);
    }

    /**
     * The filter answers before Spring MVC runs, yet its refusal has the fields of every other error:
     * here a malformed idempotency key, refused by MVC on the same path.
     */
    @Test
    void refusesAMalformedIdWithTheFieldsOfEveryOtherError() throws Exception {
        var byTheFilter = mvc.get().uri("/wallets/me").with(aToken()).header(HEADER, "has space").exchange();
        var byMvc = mvc.post().uri("/wallets/me/deposits").with(aToken()).header("Idempotency-Key", "has space")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": \"1.00\"}").exchange();

        assertThat(byMvc).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(fieldsOf(byTheFilter)).isEqualTo(fieldsOf(byMvc));
    }

    private Set<?> fieldsOf(MvcTestResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsString(), Map.class).keySet();
    }

    private static RequestPostProcessor aToken() {
        return jwt().jwt(token -> token.subject(anOwner().value()));
    }
}
