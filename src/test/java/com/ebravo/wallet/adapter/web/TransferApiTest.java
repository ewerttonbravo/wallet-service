package com.ebravo.wallet.adapter.web;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.domain.OwnerId;
import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Transfers between two users' wallets. Every refusal is followed by a check of both balances: they
 * only change when a transfer is accepted, which the positive tests show.
 */
@IntegrationTest
class TransferApiTest {

    private static final String TRANSFERS = "/wallets/me/transfers";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String LOCATION = "/wallets/me/transactions/\\p{XDigit}{8}(-\\p{XDigit}{4}){3}-\\p{XDigit}{12}";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void transfersFromTheCallersWalletToTheRecipient() {
        var alice = aWalletOwnerWith("100.00");
        var bob = aWalletOwnerWith("0.00");

        var transfer = mvc.post().uri(TRANSFERS).with(tokenOf(alice.owner())).header("X-Correlation-Id", "req-1")
                .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(transferTo(bob.wallet(), "30.00")).exchange();

        assertThat(transfer).hasStatus(HttpStatus.CREATED);
        assertThat(transfer.getResponse().getHeader(HttpHeaders.LOCATION)).matches(LOCATION);
        assertThat(transfer).bodyJson().isLenientlyEqualTo("""
                {"type": "TRANSFER", "amount": "-30.00", "balance": "70.00", "currency": "BRL",
                 "counterpartyWalletId": "%s", "correlationId": "req-1"}
                """.formatted(bob.wallet()));
        assertBalance(alice, "70.00");
        assertBalance(bob, "30.00");
    }

    @Test
    void theRecipientSeesTheTransferWithTheSenderAsCounterparty() {
        var alice = aWalletOwnerWith("100.00");
        var bob = aWalletOwnerWith("0.00");
        var location = transfer(alice, transferTo(bob.wallet(), "30.00")).getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(mvc.get().uri(location).with(tokenOf(bob.owner()))).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"type": "TRANSFER", "amount": "30.00", "balance": "30.00", "counterpartyWalletId": "%s"}
                """.formatted(alice.wallet()));
    }

    // The whole balance is transferred: had the retry been posted again, it would have been refused.
    @Test
    void replaysATransferSentAgainWithTheSameKey() throws Exception {
        var alice = aWalletOwnerWith("30.00");
        var bob = aWalletOwnerWith("0.00");
        var first = transfer(alice, "transfer-1", transferTo(bob.wallet(), "30.00"));

        var retry = transfer(alice, "transfer-1", transferTo(bob.wallet(), "30.00"));

        assertThat(retry).hasStatus(HttpStatus.CREATED)
                .hasHeader(HttpHeaders.LOCATION, first.getResponse().getHeader(HttpHeaders.LOCATION));
        assertThat(retry).bodyJson().isStrictlyEqualTo(first.getResponse().getContentAsString());
        assertBalance(alice, "0.00");
        assertBalance(bob, "30.00");
    }

    // Same key and amount, another recipient: replaying would tell Alice she paid Carol.
    @Test
    void refusesATransferKeyReusedForAnotherRecipient() {
        var alice = aWalletOwnerWith("100.00");
        var bob = aWalletOwnerWith("0.00");
        var carol = aWalletOwnerWith("0.00");
        transfer(alice, "transfer-1", transferTo(bob.wallet(), "10.00"));

        var reused = transfer(alice, "transfer-1", transferTo(carol.wallet(), "10.00"));

        assertProblem(reused, HttpStatus.UNPROCESSABLE_CONTENT, "The Idempotency-Key was already used for another request");
        assertBalance(alice, "90.00");
        assertBalance(bob, "10.00");
        assertBalance(carol, "0.00");
    }

    // The same transfer with a key is accepted (first test), so only the missing key explains the refusal.
    @Test
    void refusesATransferWithoutIdempotencyKey() {
        var alice = aWalletOwnerWith("100.00");
        var bob = aWalletOwnerWith("0.00");

        var result = mvc.post().uri(TRANSFERS).with(tokenOf(alice.owner()))
                .contentType(MediaType.APPLICATION_JSON).content(transferTo(bob.wallet(), "10.00")).exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        assertBalance(alice, "100.00");
        assertBalance(bob, "0.00");
    }

    // Every malformed form is pinned on deposits (TransactionApiTest); transfers check the same format.
    @Test
    void refusesAMalformedIdempotencyKey() {
        var alice = aWalletOwnerWith("100.00");
        var bob = aWalletOwnerWith("0.00");

        assertProblem(transfer(alice, "has space", transferTo(bob.wallet(), "10.00")), HttpStatus.BAD_REQUEST,
                "Idempotency-Key must be 1 to 64 letters, digits, '.', '_', ':' or '-'");
        assertBalance(alice, "100.00");
        assertBalance(bob, "0.00");
    }

    // The whole balance can be transferred (above), so only the extra cent explains the refusal.
    @Test
    void refusesATransferAboveTheBalance() {
        var alice = aWalletOwnerWith("100.00");
        var bob = aWalletOwnerWith("0.00");

        assertProblem(transfer(alice, transferTo(bob.wallet(), "100.01")), HttpStatus.UNPROCESSABLE_CONTENT,
                "The balance is not enough for this transfer");
        assertThat(transfer(alice, transferTo(bob.wallet(), "100.00"))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void refusesATransferToTheCallersOwnWallet() {
        var alice = aWalletOwnerWith("100.00");

        assertProblem(transfer(alice, transferTo(alice.wallet(), "10.00")), HttpStatus.UNPROCESSABLE_CONTENT,
                "A wallet cannot transfer to itself");
        assertBalance(alice, "100.00");
    }

    // The same request to an existing wallet succeeds (first test), so only the unknown id explains it.
    @Test
    void refusesATransferToAnUnknownWallet() {
        var alice = aWalletOwnerWith("100.00");

        assertProblem(transfer(alice, transferTo(UUID.randomUUID().toString(), "10.00")), HttpStatus.UNPROCESSABLE_CONTENT,
                "The recipient wallet does not exist");
        assertBalance(alice, "100.00");
    }

    @Test
    void refusesATransferThatWouldTakeTheRecipientPastTheLargestStorableBalance() {
        var alice = aWalletOwnerWith("0.01");
        var bob = aWalletOwnerWith("99999999999999999.99");

        assertProblem(transfer(alice, transferTo(bob.wallet(), "0.01")), HttpStatus.UNPROCESSABLE_CONTENT,
                "The recipient's balance would exceed 99999999999999999.99");
        assertBalance(alice, "0.01");
        assertBalance(bob, "99999999999999999.99");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0.00   | Amount must be positive: 0.00",
            "-1     | Amount must be positive: -1.00",
            "1.001  | Amount must have at most 2 decimal places: 1.001"})
    void refusesAnAmountThatBreaksTheRules(String amount, String detail) {
        var alice = aWalletOwnerWith("100.00");
        var bob = aWalletOwnerWith("0.00");

        assertProblem(transfer(alice, transferTo(bob.wallet(), amount)), HttpStatus.UNPROCESSABLE_CONTENT, detail);
        assertBalance(alice, "100.00");
        assertBalance(bob, "0.00");
    }

    static Stream<String> malformedBodies() {
        var recipient = "\"recipientWalletId\": \"%s\"";
        return Stream.of(
                "{\"amount\": \"1.00\"}",
                "{\"recipientWalletId\": null, \"amount\": \"1.00\"}",
                "{\"recipientWalletId\": 42, \"amount\": \"1.00\"}",
                "{\"recipientWalletId\": \"abc\", \"amount\": \"1.00\"}",
                // UUID.fromString accepts this; it is not a wallet id as the API writes them.
                "{\"recipientWalletId\": \"1-1-1-1-1\", \"amount\": \"1.00\"}",
                "{" + recipient + "}",
                "{" + recipient + ", \"amount\": 1.00}",
                "{" + recipient + ", \"amount\": \"abc\"}",
                "not json",
                "");
    }

    @ParameterizedTest
    @MethodSource("malformedBodies")
    void refusesAMalformedRequest(String body) {
        var alice = aWalletOwnerWith("100.00");
        var bob = aWalletOwnerWith("0.00");

        var result = transfer(alice, body.replace("%s", bob.wallet()));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertBalance(alice, "100.00");
        assertBalance(bob, "0.00");
    }

    @Test
    void explainsHowToWriteTheRecipient() {
        var alice = aWalletOwnerWith("100.00");

        assertProblem(transfer(alice, "{\"recipientWalletId\": \"abc\", \"amount\": \"1.00\"}"), HttpStatus.BAD_REQUEST,
                "recipientWalletId must be a wallet id such as \"0b6a9c7e-3f1d-4e2a-9c5b-7d8e1f2a3b4c\"");
    }

    @Test
    void reportsACallerWithoutWallet() {
        var bob = aWalletOwnerWith("0.00");

        var result = mvc.post().uri(TRANSFERS).with(tokenOf(anOwner())).header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(transferTo(bob.wallet(), "10.00")).exchange();

        assertProblem(result, HttpStatus.NOT_FOUND, "The user has no wallet");
        assertBalance(bob, "0.00");
    }

    @Test
    void rejectsRequestsWithoutToken() {
        var result = mvc.post().uri(TRANSFERS)
                .contentType(MediaType.APPLICATION_JSON).content(transferTo(UUID.randomUUID().toString(), "10.00")).exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    private WalletOwner aWalletOwnerWith(String balance) {
        var owner = anOwner();
        var opened = mvc.post().uri("/wallets").with(tokenOf(owner)).exchange();
        assertThat(opened).hasStatus(HttpStatus.CREATED);
        var walletOwner = new WalletOwner(owner, read(opened, "$.id"));
        if (!balance.equals("0.00")) {
            assertThat(mvc.post().uri("/wallets/me/deposits").with(tokenOf(owner)).contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                    .content("{\"amount\": \"" + balance + "\"}")).hasStatus(HttpStatus.CREATED);
        }
        return walletOwner;
    }

    /** Each call is a new request: it carries a fresh idempotency key. */
    private MvcTestResult transfer(WalletOwner from, String body) {
        return transfer(from, UUID.randomUUID().toString(), body);
    }

    private MvcTestResult transfer(WalletOwner from, String idempotencyKey, String body) {
        return mvc.post().uri(TRANSFERS).with(tokenOf(from.owner())).header(IDEMPOTENCY_KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private void assertBalance(WalletOwner walletOwner, String balance) {
        assertThat(mvc.get().uri("/wallets/me/balance").with(tokenOf(walletOwner.owner())))
                .bodyJson().extractingPath("$.balance").isEqualTo(balance);
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String detail) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo(detail);
    }

    private static String read(MvcTestResult result, String path) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), path);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String transferTo(String wallet, String amount) {
        return "{\"recipientWalletId\": \"" + wallet + "\", \"amount\": \"" + amount + "\"}";
    }

    private static RequestPostProcessor tokenOf(OwnerId owner) {
        return jwt().jwt(token -> token.subject(owner.value()));
    }

    private record WalletOwner(OwnerId owner, String wallet) {
    }
}
