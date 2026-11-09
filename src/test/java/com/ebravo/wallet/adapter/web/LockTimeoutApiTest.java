package com.ebravo.wallet.adapter.web;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.domain.OwnerId;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class LockTimeoutApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    /**
     * Only the wallet lock gives up early. A session-wide limit would also hit migrations, whose
     * table locks may wait for in-flight postings during a rolling deploy.
     */
    @Test
    void leavesOtherStatementsWithTheServerLockTimeout() {
        assertThat(jdbc.sql("show lock_timeout").query(String.class).single()).isEqualTo("0");
    }

    /**
     * Another transaction holds the wallet's row. The deposit gives up instead of waiting forever,
     * and succeeds once the row is released: so the refusal came from the lock, and changed nothing.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void answersServiceUnavailableWhileTheWalletStaysLocked() throws SQLException {
        var owner = anOwner();
        assertThat(mvc.post().uri("/wallets").with(tokenOf(owner))).hasStatus(HttpStatus.CREATED);

        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockWalletOf(owner, holder);

            assertThat(deposit(owner))
                    .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .hasHeader(HttpHeaders.RETRY_AFTER, "1")
                    .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson().extractingPath("$.detail").isEqualTo("The wallet is busy, retry the request");
            holder.rollback();
        }

        assertThat(deposit(owner)).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.balance").isEqualTo("10.00");
    }

    /**
     * A transfer locks the recipient too: while it is held elsewhere the transfer gives up, and once
     * released the same transfer goes through.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aTransferWaitsForTheRecipientsLockToo() throws Exception {
        var alice = anOwner();
        var bob = anOwner();
        assertThat(mvc.post().uri("/wallets").with(tokenOf(alice))).hasStatus(HttpStatus.CREATED);
        var bobWallet = mvc.post().uri("/wallets").with(tokenOf(bob)).exchange();
        assertThat(bobWallet).hasStatus(HttpStatus.CREATED);
        var recipient = JsonPath.<String>read(bobWallet.getResponse().getContentAsString(), "$.id");
        assertThat(deposit(alice)).hasStatus(HttpStatus.CREATED);

        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockWalletOf(bob, holder);

            assertThat(transfer(alice, recipient))
                    .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .hasHeader(HttpHeaders.RETRY_AFTER, "1");
            holder.rollback();
        }

        assertThat(transfer(alice, recipient)).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.balance").isEqualTo("6.00");
    }

    // Same locked wallet as above, where a valid deposit waits and gets 503: this one never waits.
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void refusesAnInvalidAmountWithoutWaitingForTheLock() throws SQLException {
        var owner = anOwner();
        assertThat(mvc.post().uri("/wallets").with(tokenOf(owner))).hasStatus(HttpStatus.CREATED);

        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockWalletOf(owner, holder);

            assertThat(deposit(owner, "0.00")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            holder.rollback();
        }
    }

    private static void lockWalletOf(OwnerId owner, Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("select id from wallet where owner_id = ? for update")) {
            statement.setString(1, owner.value());
            assertThat(statement.executeQuery().next()).isTrue();
        }
    }

    private MvcTestResult deposit(OwnerId owner) {
        return deposit(owner, "10.00");
    }

    private MvcTestResult deposit(OwnerId owner, String amount) {
        return mvc.post().uri("/wallets/me/deposits").with(tokenOf(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": \"" + amount + "\"}").exchange();
    }

    private MvcTestResult transfer(OwnerId owner, String recipient) {
        return mvc.post().uri("/wallets/me/transfers").with(tokenOf(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientWalletId\": \"" + recipient + "\", \"amount\": \"4.00\"}").exchange();
    }

    private static RequestPostProcessor tokenOf(OwnerId owner) {
        return jwt().jwt(token -> token.subject(owner.value()));
    }
}
