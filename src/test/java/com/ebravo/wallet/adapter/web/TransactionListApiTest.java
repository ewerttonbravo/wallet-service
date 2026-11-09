package com.ebravo.wallet.adapter.web;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.domain.OwnerId;
import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The caller's history: every transaction of their wallet, newest first, a page at a time. */
@IntegrationTest
class TransactionListApiTest {

    private static final String TRANSACTIONS = "/wallets/me/transactions";
    private static final String PAGE_DETAIL = "page must be a whole number from 0";
    private static final String SIZE_DETAIL = "size must be a whole number from 1 to 100";

    @Autowired
    private MockMvcTester mvc;

    // Each item must be byte for byte what reading that transaction on its own returns.
    @Test
    void listsTheCallersTransactionsNewestFirstAsEachReadsOnItsOwn() throws Exception {
        var owner = aWalletOwner();
        var deposit = post(owner, "/wallets/me/deposits", "10.50");
        var withdrawal = post(owner, "/wallets/me/withdrawals", "0.50");

        var list = list(owner, TRANSACTIONS);

        assertThat(list).hasStatusOk();
        assertThat(list).bodyJson().isLenientlyEqualTo("""
                {"page": 0, "size": 20, "totalElements": 2, "totalPages": 1}
                """);
        assertThat(items(list)).containsExactly(read(owner, withdrawal), read(owner, deposit));
        assertThat(list).bodyJson().doesNotHavePath("$.next");
    }

    @Test
    void linksTheNextPageUntilTheLast() throws Exception {
        var owner = aWalletOwner();
        var first = id(post(owner, "/wallets/me/deposits", "1.00"));
        var second = id(post(owner, "/wallets/me/deposits", "2.00"));
        var third = id(post(owner, "/wallets/me/deposits", "3.00"));

        var newest = list(owner, TRANSACTIONS + "?size=2");
        assertThat(newest).bodyJson().isLenientlyEqualTo("""
                {"page": 0, "size": 2, "totalElements": 3, "totalPages": 2,
                 "next": "/wallets/me/transactions?page=1&size=2"}
                """);
        assertThat(ids(newest)).containsExactly(third, second);

        var oldest = list(owner, JsonPath.read(body(newest), "$.next"));
        assertThat(oldest).bodyJson().isLenientlyEqualTo("""
                {"page": 1, "size": 2, "totalElements": 3, "totalPages": 2}
                """);
        assertThat(ids(oldest)).containsExactly(first);
        assertThat(oldest).bodyJson().doesNotHavePath("$.next");
    }

    @Test
    void answersAPagePastTheEndWithNoTransactionsAndTheTotals() throws Exception {
        var owner = aWalletOwner();
        post(owner, "/wallets/me/deposits", "1.00");

        var list = list(owner, TRANSACTIONS + "?page=5");

        assertThat(list).hasStatusOk();
        assertThat(list).bodyJson().isLenientlyEqualTo("""
                {"transactions": [], "page": 5, "size": 20, "totalElements": 1, "totalPages": 1}
                """);
        assertThat(list).bodyJson().doesNotHavePath("$.next");
    }

    @Test
    void listsNothingForANewWallet() {
        assertThat(list(aWalletOwner(), TRANSACTIONS)).bodyJson().isLenientlyEqualTo("""
                {"transactions": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0}
                """);
    }

    // Bob's own deposit is listed, so not seeing Alice's is not an empty answer.
    @Test
    void listsOnlyTheCallersOwnTransactions() throws Exception {
        var alice = aWalletOwner();
        var bob = aWalletOwner();
        var hers = id(post(alice, "/wallets/me/deposits", "10.00"));
        var his = id(post(bob, "/wallets/me/deposits", "20.00"));

        var list = list(bob, TRANSACTIONS);

        assertThat(ids(list)).containsExactly(his).doesNotContain(hers);
        assertThat(list).bodyJson().extractingPath("$.totalElements").isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=0&size=1", "?size=100", "?page=2147483647"})
    void acceptsTheBoundsOfAPage(String query) {
        assertThat(list(aWalletOwner(), TRANSACTIONS + query)).hasStatusOk();
    }

    // The detail is fixed text: the rejected value is never echoed back.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "?page=-1             | " + PAGE_DETAIL,
            "?page=one            | " + PAGE_DETAIL,
            "?page=1.5            | " + PAGE_DETAIL,
            "?page=               | " + PAGE_DETAIL,
            "?page=2147483648     | " + PAGE_DETAIL,
            "?size=0              | " + SIZE_DETAIL,
            "?size=101            | " + SIZE_DETAIL,
            "?size=twenty         | " + SIZE_DETAIL,
            "?size=99999999999    | " + SIZE_DETAIL})
    void refusesAPageOutOfBounds(String query, String detail) {
        var result = list(aWalletOwner(), TRANSACTIONS + query);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo(detail);
    }

    @Test
    void reportsAUserWithoutWallet() {
        var result = list(anOwner(), TRANSACTIONS);

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("The user has no wallet");
    }

    @Test
    void rejectsARequestWithoutToken() {
        var result = mvc.get().uri(TRANSACTIONS).exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    private OwnerId aWalletOwner() {
        var owner = anOwner();
        assertThat(mvc.post().uri("/wallets").with(tokenOf(owner))).hasStatus(HttpStatus.CREATED);
        return owner;
    }

    private MvcTestResult post(OwnerId owner, String path, String amount) {
        var result = mvc.post().uri(path).with(tokenOf(owner)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": \"" + amount + "\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return result;
    }

    private MvcTestResult list(OwnerId owner, String uri) {
        return mvc.get().uri(uri).with(tokenOf(owner)).exchange();
    }

    private Object read(OwnerId owner, MvcTestResult posted) throws UnsupportedEncodingException {
        var location = posted.getResponse().getHeader(HttpHeaders.LOCATION);
        return JsonPath.read(body(mvc.get().uri(location).with(tokenOf(owner)).exchange()), "$");
    }

    private static List<Object> items(MvcTestResult list) throws UnsupportedEncodingException {
        return JsonPath.read(body(list), "$.transactions[*]");
    }

    private static List<String> ids(MvcTestResult list) throws UnsupportedEncodingException {
        return JsonPath.read(body(list), "$.transactions[*].id");
    }

    private static String id(MvcTestResult posted) throws UnsupportedEncodingException {
        return JsonPath.read(body(posted), "$.id");
    }

    private static String body(MvcTestResult result) throws UnsupportedEncodingException {
        return result.getResponse().getContentAsString();
    }

    private static RequestPostProcessor tokenOf(OwnerId owner) {
        return jwt().jwt(token -> token.subject(owner.value()));
    }
}
