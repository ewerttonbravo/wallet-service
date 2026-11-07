package com.ebravo.wallet.adapter.web;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.domain.OwnerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class WalletApiTest {

    private static final String ISO_8601_UTC = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void opensTheWalletOfTheCaller() {
        var opened = open(anOwner());

        assertThat(opened).hasStatus(HttpStatus.CREATED).hasHeader(HttpHeaders.LOCATION, "/wallets/me");
        assertThat(opened).bodyJson().extractingPath("$.id").asString()
                .matches("\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}");
        assertThat(opened).bodyJson().extractingPath("$.currency").isEqualTo("BRL");
        assertThat(opened).bodyJson().extractingPath("$.createdAt").asString().matches(ISO_8601_UTC);
    }

    @Test
    void refusesASecondWalletForTheSameUser() throws Exception {
        var owner = anOwner();
        var first = open(owner).getResponse().getContentAsString();

        assertThat(open(owner))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {"status": 409, "title": "Conflict", "detail": "The user already has a wallet"}
                        """);
        assertThat(mvc.get().uri("/wallets/me").with(tokenOf(owner))).bodyJson().isStrictlyEqualTo(first);
    }

    @Test
    void returnsTheWalletItOpened() throws Exception {
        var owner = anOwner();
        var opened = open(owner).getResponse().getContentAsString();

        assertThat(mvc.get().uri("/wallets/me").with(tokenOf(owner)))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo(opened);
    }

    @Test
    void newWalletHasZeroBalanceWrittenAsDecimalString() {
        var owner = anOwner();
        open(owner);

        assertThat(mvc.get().uri("/wallets/me/balance").with(tokenOf(owner)))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {"balance": "0.00", "currency": "BRL"}
                        """);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/wallets/me", "/wallets/me/balance"})
    void reportsAUserWithoutWallet(String path) {
        assertThat(mvc.get().uri(path).with(tokenOf(anOwner())))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {"status": 404, "title": "Not Found", "detail": "The user has no wallet"}
                        """);
    }

    @ParameterizedTest
    @CsvSource({"POST, /wallets", "GET, /wallets/me", "GET, /wallets/me/balance"})
    void rejectsRequestsWithoutToken(String method, String path) {
        var result = mvc.method(HttpMethod.valueOf(method)).uri(path).exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    @Test
    void keepsWalletsOfDifferentUsersApart() throws Exception {
        var alice = anOwner();
        var bob = anOwner();
        var aliceWallet = open(alice).getResponse().getContentAsString();

        assertThat(mvc.get().uri("/wallets/me").with(tokenOf(bob))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/wallets/me/balance").with(tokenOf(bob))).hasStatus(HttpStatus.NOT_FOUND);

        var bobWallet = open(bob);
        assertThat(bobWallet).hasStatus(HttpStatus.CREATED);
        assertThat(bobWallet).bodyJson().isNotStrictlyEqualTo(aliceWallet);
        assertThat(mvc.get().uri("/wallets/me").with(tokenOf(alice))).bodyJson().isStrictlyEqualTo(aliceWallet);
    }

    private MvcTestResult open(OwnerId owner) {
        return mvc.post().uri("/wallets").with(tokenOf(owner)).exchange();
    }

    private static RequestPostProcessor tokenOf(OwnerId owner) {
        return jwt().jwt(token -> token.subject(owner.value()));
    }
}
