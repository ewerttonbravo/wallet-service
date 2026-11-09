package com.ebravo.wallet;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The check every API test runs through: it must refuse what the contract does not describe. */
class ApiContractTest {

    private static final ApiContract CONTRACT = ApiContract.load();

    private static final String WALLET = """
            {"id":"0b6a9c7e-3f1d-4e2a-9c5b-7d8e1f2a3b4c","currency":"BRL","createdAt":"2026-11-09T10:00:00.123456Z"}""";
    private static final String DEPOSIT = """
            {"id":"5d3f1c2a-8b7e-4a6d-9c1b-2e3f4a5b6c7d","type":"DEPOSIT","amount":"10.50","balance":"10.50",\
            "currency":"BRL","correlationId":"my-request-1","createdAt":"2026-11-09T10:00:00.123456Z"}""";
    private static final String MISSING_KEY = """
            {"type":"about:blank","title":"Bad Request","status":400,\
            "detail":"Idempotency-Key header is required","instance":"/wallets/me/deposits"}""";

    @Test
    void acceptsAResponseTheContractDescribes() {
        assertThatCode(() -> CONTRACT.verify(get("/wallets/me"), response(HttpStatus.OK, MediaType.APPLICATION_JSON, WALLET)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAFieldTheContractDoesNotDeclare() {
        var withOwner = WALLET.replace("}", ",\"ownerId\":\"alice\"}");

        assertThatThrownBy(() -> CONTRACT.verify(get("/wallets/me"), response(HttpStatus.OK, MediaType.APPLICATION_JSON, withOwner)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ownerId");
    }

    @Test
    void rejectsAStatusTheContractDoesNotDeclare() {
        // 409 is documented for opening a wallet, not for reading one.
        var conflict = response(HttpStatus.CONFLICT, MediaType.APPLICATION_PROBLEM_JSON, MISSING_KEY);

        assertThatThrownBy(() -> CONTRACT.verify(get("/wallets/me"), conflict))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Response status 409 not defined");
    }

    @Test
    void acceptsASuccessToARequestTheContractDescribes() {
        var request = deposit();
        request.addHeader("Idempotency-Key", "deposit-1");

        assertThatCode(() -> CONTRACT.verify(request, created(DEPOSIT))).doesNotThrowAnyException();
    }

    @Test
    void rejectsASuccessToARequestTheContractRefuses() {
        assertThatThrownBy(() -> CONTRACT.verify(deposit(), created(DEPOSIT)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Idempotency-Key");
    }

    /** Error tests send such requests on purpose; only the response is checked then. */
    @Test
    void acceptsARefusalOfARequestTheContractRefuses() {
        var refused = response(HttpStatus.BAD_REQUEST, MediaType.APPLICATION_PROBLEM_JSON, MISSING_KEY);

        assertThatCode(() -> CONTRACT.verify(deposit(), refused)).doesNotThrowAnyException();
    }

    private static MockHttpServletRequest get(String path) {
        return request("GET", path);
    }

    private static MockHttpServletRequest deposit() {
        var request = request("POST", "/wallets/me/deposits");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("{\"amount\":\"10.50\"}".getBytes(StandardCharsets.UTF_8));
        return request;
    }

    // MockMvc sets the path info as well; the validator reads the path from it.
    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setPathInfo(path);
        return request;
    }

    private static MockHttpServletResponse created(String body) {
        var response = response(HttpStatus.CREATED, MediaType.APPLICATION_JSON, body);
        response.setHeader(HttpHeaders.LOCATION, "/wallets/me/transactions/5d3f1c2a-8b7e-4a6d-9c1b-2e3f4a5b6c7d");
        return response;
    }

    private static MockHttpServletResponse response(HttpStatus status, MediaType type, String body) {
        var response = new MockHttpServletResponse();
        response.setStatus(status.value());
        response.setContentType(type.toString());
        response.setHeader("X-Correlation-Id", "my-request-1");
        try {
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return response;
    }
}
