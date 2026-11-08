package com.ebravo.wallet.adapter.web;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.domain.OwnerId;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Deposits, withdrawals and the transactions they post. Every refusal is followed by a balance
 * check: the balance only changes when a posting is accepted, which the positive tests show.
 */
@IntegrationTest
class TransactionApiTest {

    private static final String DEPOSITS = "/wallets/me/deposits";
    private static final String WITHDRAWALS = "/wallets/me/withdrawals";
    private static final String CORRELATION = "X-Correlation-Id";
    private static final String ISO_8601_UTC = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";
    private static final String LOCATION = "/wallets/me/transactions/\\p{XDigit}{8}(-\\p{XDigit}{4}){3}-\\p{XDigit}{12}";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void depositsIntoTheCallersWallet() {
        var owner = aWalletOwner();

        var deposit = mvc.post().uri(DEPOSITS).with(tokenOf(owner)).header(CORRELATION, "req-1")
                .contentType(MediaType.APPLICATION_JSON).content(amount("10.50")).exchange();

        assertThat(deposit).hasStatus(HttpStatus.CREATED).hasHeader(CORRELATION, "req-1");
        assertThat(deposit.getResponse().getHeader(HttpHeaders.LOCATION))
                .matches(LOCATION)
                .endsWith(id(deposit));
        assertThat(deposit).bodyJson().isLenientlyEqualTo("""
                {"type": "DEPOSIT", "amount": "10.50", "balance": "10.50", "currency": "BRL", "correlationId": "req-1"}
                """);
        assertThat(deposit).bodyJson().extractingPath("$.createdAt").asString().matches(ISO_8601_UTC);
        assertBalance(owner, "10.50");
    }

    @Test
    void withdrawsFromTheCallersWallet() {
        var owner = aWalletOwner();
        post(owner, DEPOSITS, amount("10.50"));

        var withdrawal = post(owner, WITHDRAWALS, amount("10.50"));

        assertThat(withdrawal).hasStatus(HttpStatus.CREATED);
        assertThat(withdrawal).bodyJson().isLenientlyEqualTo("""
                {"type": "WITHDRAWAL", "amount": "-10.50", "balance": "0.00", "currency": "BRL"}
                """);
        assertBalance(owner, "0.00");
    }

    @Test
    void returnsExactlyTheTransactionItPosted() throws Exception {
        var owner = aWalletOwner();
        var deposit = post(owner, DEPOSITS, amount("10.50"));

        assertThat(mvc.get().uri(deposit.getResponse().getHeader(HttpHeaders.LOCATION)).with(tokenOf(owner)))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo(deposit.getResponse().getContentAsString());
    }

    @Test
    void recordsTheCorrelationIdItGeneratedWhenTheCallerSentNone() {
        var deposit = post(aWalletOwner(), DEPOSITS, amount("10.50"));

        assertThat(deposit).bodyJson().extractingPath("$.correlationId")
                .isEqualTo(deposit.getResponse().getHeader(CORRELATION));
    }

    // The whole balance can be withdrawn (above), so only the extra cent explains the refusal.
    @Test
    void refusesAWithdrawalAboveTheBalance() {
        var owner = aWalletOwner();
        post(owner, DEPOSITS, amount("10.50"));

        assertProblem(post(owner, WITHDRAWALS, amount("10.51")), HttpStatus.UNPROCESSABLE_CONTENT,
                "The balance is not enough for this withdrawal");
        assertBalance(owner, "10.50");
    }

    @Test
    void refusesADepositThatWouldExceedTheLargestStorableBalance() {
        var owner = aWalletOwner();
        assertThat(post(owner, DEPOSITS, amount("99999999999999999.99"))).hasStatus(HttpStatus.CREATED);

        assertProblem(post(owner, DEPOSITS, amount("0.01")), HttpStatus.UNPROCESSABLE_CONTENT,
                "The balance would exceed 99999999999999999.99");
        assertBalance(owner, "99999999999999999.99");
    }

    static Stream<Arguments> malformedBodies() {
        return Stream.of(DEPOSITS, WITHDRAWALS).flatMap(path -> Stream.of(
                "{\"amount\": 1.00}", "{\"amount\": 1}", "{\"amount\": true}", "{\"amount\": null}", "{}",
                "{\"amount\": \"abc\"}", "{\"amount\": \"1e3\"}", "{\"amount\": \" 1.00\"}", "{\"amount\": \"1.\"}",
                "{\"amount\": \"" + "1".repeat(33) + "\"}", "not json", "")
                .map(body -> Arguments.of(path, body)));
    }

    @ParameterizedTest
    @MethodSource("malformedBodies")
    void refusesAMalformedRequest(String path, String body) {
        var owner = aWalletOwner();
        post(owner, DEPOSITS, amount("10.00"));

        var result = post(owner, path, body);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertBalance(owner, "10.00");
    }

    @ParameterizedTest
    @CsvSource({"{\"amount\": null}", "{\"amount\": \"abc\"}"})
    void explainsHowToWriteAnAmount(String body) {
        assertProblem(post(aWalletOwner(), DEPOSITS, body), HttpStatus.BAD_REQUEST,
                "amount must be a decimal string such as \"10.50\"");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/wallets/me/deposits    | 0.00                  | Amount must be positive: 0.00",
            "/wallets/me/deposits    | -1                    | Amount must be positive: -1.00",
            "/wallets/me/deposits    | 1.001                 | Amount must have at most 2 decimal places: 1.001",
            "/wallets/me/deposits    | 100000000000000000.00 | Amount must not exceed 99999999999999999.99 in magnitude: 100000000000000000.00",
            "/wallets/me/withdrawals | 0                     | Amount must be positive: 0.00",
            "/wallets/me/withdrawals | -0.01                 | Amount must be positive: -0.01",
            "/wallets/me/withdrawals | 0.009                 | Amount must have at most 2 decimal places: 0.009"})
    void refusesAnAmountThatBreaksTheRules(String path, String value, String detail) {
        var owner = aWalletOwner();
        post(owner, DEPOSITS, amount("10.00"));

        assertProblem(post(owner, path, amount(value)), HttpStatus.UNPROCESSABLE_CONTENT, detail);
        assertBalance(owner, "10.00");
    }

    @ParameterizedTest
    @CsvSource({DEPOSITS, WITHDRAWALS})
    void reportsAUserWithoutWallet(String path) {
        assertProblem(post(anOwner(), path, amount("10.00")), HttpStatus.NOT_FOUND, "The user has no wallet");
    }

    // Alice reads her own transaction at the same address, so Bob's 404 can only mean it is not his.
    @Test
    void hidesATransactionFromAnotherUser() {
        var alice = aWalletOwner();
        var bob = aWalletOwner();
        var location = post(alice, DEPOSITS, amount("10.50")).getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(mvc.get().uri(location).with(tokenOf(alice))).hasStatusOk();
        assertProblem(mvc.get().uri(location).with(tokenOf(bob)).exchange(), HttpStatus.NOT_FOUND,
                "The user has no such transaction");
    }

    @Test
    void reportsAnUnknownTransaction() {
        assertProblem(mvc.get().uri("/wallets/me/transactions/" + UUID.randomUUID()).with(tokenOf(aWalletOwner())).exchange(),
                HttpStatus.NOT_FOUND, "The user has no such transaction");
    }

    @Test
    void refusesATransactionIdThatIsNotAUuid() {
        assertThat(mvc.get().uri("/wallets/me/transactions/42").with(tokenOf(aWalletOwner())))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @ParameterizedTest
    @CsvSource({"POST, /wallets/me/deposits", "POST, /wallets/me/withdrawals", "GET, /wallets/me/transactions/6f1c2e9a-0d5b-4c4e-9a43-5d8f0b1e2c3d"})
    void rejectsRequestsWithoutToken(String method, String path) {
        var result = mvc.method(HttpMethod.valueOf(method)).uri(path)
                .contentType(MediaType.APPLICATION_JSON).content(amount("10.00")).exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    private OwnerId aWalletOwner() {
        var owner = anOwner();
        assertThat(mvc.post().uri("/wallets").with(tokenOf(owner))).hasStatus(HttpStatus.CREATED);
        return owner;
    }

    private MvcTestResult post(OwnerId owner, String path, String body) {
        return mvc.post().uri(path).with(tokenOf(owner)).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private void assertBalance(OwnerId owner, String balance) {
        assertThat(mvc.get().uri("/wallets/me/balance").with(tokenOf(owner)))
                .bodyJson().extractingPath("$.balance").isEqualTo(balance);
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String detail) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo(detail);
    }

    private static String id(MvcTestResult result) {
        try {
            return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String amount(String value) {
        return "{\"amount\": \"" + value + "\"}";
    }

    private static RequestPostProcessor tokenOf(OwnerId owner) {
        return jwt().jwt(token -> token.subject(owner.value()));
    }
}
