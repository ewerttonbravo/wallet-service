package com.ebravo.wallet.adapter.web;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.domain.OwnerId;
import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

/** The balance of the caller's wallet at a past instant, read from the ledger. */
@IntegrationTest
class HistoricalBalanceApiTest {

    private static final String BALANCE = "/wallets/me/balance";
    private static final String MALFORMED_DETAIL = "at must be an ISO-8601 instant with offset, such as \"2026-11-09T10:00:00Z\"";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void reportsTheBalanceAtThePastInstantOfEachPosting() throws Exception {
        var owner = anOwner();
        open(owner);
        var deposit = createdAt(post(owner, "/wallets/me/deposits", "10.50"));
        var withdrawal = createdAt(post(owner, "/wallets/me/withdrawals", "0.50"));
        // Otherwise the answer at the deposit's instant would rightly include the withdrawal.
        assertThat(withdrawal).isAfter(deposit);

        assertThat(balanceAt(owner, deposit.toString())).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {"balance": "10.50", "currency": "BRL"}
                """);
        assertThat(balanceAt(owner, withdrawal.toString())).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {"balance": "10.00", "currency": "BRL"}
                """);
    }

    // The same instant three hours behind UTC: read as local time, it would land after both postings.
    @Test
    void honoursTheOffsetOfTheInstant() throws Exception {
        var owner = anOwner();
        open(owner);
        var deposit = createdAt(post(owner, "/wallets/me/deposits", "10.50"));
        var withdrawal = createdAt(post(owner, "/wallets/me/withdrawals", "0.50"));
        assertThat(withdrawal).isAfter(deposit);

        var inBrasilia = deposit.atOffset(ZoneOffset.ofHours(-3)).toString();

        assertThat(balanceAt(owner, inBrasilia)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {"balance": "10.50", "currency": "BRL"}
                """);
    }

    // The wallet holds a deposit, so zero is the balance at its opening, not the current one.
    @Test
    void reportsZeroAtTheInstantTheWalletWasOpened() throws Exception {
        var owner = anOwner();
        var opened = createdAt(open(owner));
        post(owner, "/wallets/me/deposits", "10.50");

        assertThat(balanceAt(owner, opened.toString())).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {"balance": "0.00", "currency": "BRL"}
                """);
    }

    // The opening instant itself is answered (above), so only the microsecond before it is refused.
    @Test
    void refusesAnInstantBeforeTheWallet() throws Exception {
        var owner = anOwner();
        var opened = createdAt(open(owner));

        var result = balanceAt(owner, opened.minus(1, ChronoUnit.MICROS).toString());

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("The wallet did not exist at that instant");
    }

    @Test
    void refusesAnInstantInTheFuture() throws Exception {
        var owner = anOwner();
        open(owner);

        var result = balanceAt(owner, Instant.now().plus(1, ChronoUnit.DAYS).toString());

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("The instant is in the future");
    }

    // Well-formed instants are answered above; the detail is fixed text and never echoes the value.
    @ParameterizedTest
    @ValueSource(strings = {
            "yesterday",
            "2026-11-09T10:00:00",
            "2026-11-09",
            "2026-13-01T00:00:00Z",
            "2026-11-09T10:00:00 03:00",
            "",
            "11111111111111111111111111111111111111111111111111111111111111111"})
    void refusesAMalformedInstantWithoutEchoingIt(String at) throws Exception {
        var owner = anOwner();
        open(owner);

        var result = balanceAt(owner, at);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo(MALFORMED_DETAIL);
        if (!at.isEmpty()) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain(at);
        }
    }

    private MvcTestResult open(OwnerId owner) {
        var result = mvc.post().uri("/wallets").with(tokenOf(owner)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return result;
    }

    private MvcTestResult post(OwnerId owner, String path, String amount) {
        var result = mvc.post().uri(path).with(tokenOf(owner)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": \"" + amount + "\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return result;
    }

    // Sent as a parameter, not in the URI: a raw "+" in a query string is read as a space.
    private MvcTestResult balanceAt(OwnerId owner, String at) {
        return mvc.get().uri(BALANCE).param("at", at).with(tokenOf(owner)).exchange();
    }

    private static Instant createdAt(MvcTestResult result) throws UnsupportedEncodingException {
        return Instant.parse(JsonPath.read(result.getResponse().getContentAsString(), "$.createdAt"));
    }

    private static RequestPostProcessor tokenOf(OwnerId owner) {
        return jwt().jwt(token -> token.subject(owner.value()));
    }
}
