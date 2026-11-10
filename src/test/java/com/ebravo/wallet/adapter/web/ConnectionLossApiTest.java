package com.ebravo.wallet.adapter.web;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.domain.OwnerId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/**
 * The database ends a posting's connection while the posting runs, as PostgreSQL does to every
 * connection when it restarts or fails over. The database itself stays up, so the shared one serves.
 * Each request waits on a row the test holds, so its connection is ended while it runs.
 */
@IntegrationTest
class ConnectionLossApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    /**
     * The deposit waits for the wallet's lock, held here, and its connection is ended meanwhile. The
     * detail is the database's, not the busy wallet's the lock wait would give; and the same request,
     * key included, posts once the lock is released, so the lost one posted nothing.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void answersServiceUnavailableWhenAPostingLosesItsConnection() throws Exception {
        var owner = anOwner();
        var key = UUID.randomUUID().toString();
        assertThat(mvc.post().uri("/wallets").with(jwt().jwt(token -> token.subject(owner.value()))))
                .hasStatus(HttpStatus.CREATED);

        try (var holder = dataSource.getConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            holder.setAutoCommit(false);
            lockWalletOf(owner, holder);
            var deposit = executor.submit(() -> deposit(owner, key));

            endTheConnectionWaitingFor(holder, deposit);

            assertThat(deposit.get())
                    .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .hasHeader(HttpHeaders.RETRY_AFTER, "1")
                    .bodyJson().extractingPath("$.detail").isEqualTo("The database is unavailable, retry the request");
            holder.rollback();
        }

        assertThat(deposit(owner, key)).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.balance").isEqualTo("10.00");
    }

    /**
     * Opening a wallet waits for another transaction opening one for the same owner, held here, and
     * its connection is ended meanwhile. Once that transaction rolls back, the same opening succeeds.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void answersServiceUnavailableWhenOpeningAWalletLosesItsConnection() throws Exception {
        var owner = anOwner();

        try (var holder = dataSource.getConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            holder.setAutoCommit(false);
            openWalletOf(owner, holder);
            var opening = executor.submit(() -> mvc.post().uri("/wallets")
                    .with(jwt().jwt(token -> token.subject(owner.value()))).exchange());

            endTheConnectionWaitingFor(holder, opening);

            assertThat(opening.get())
                    .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .bodyJson().extractingPath("$.detail").isEqualTo("The database is unavailable, retry the request");
            holder.rollback();
        }

        assertThat(mvc.post().uri("/wallets").with(jwt().jwt(token -> token.subject(owner.value()))))
                .hasStatus(HttpStatus.CREATED);
    }

    /**
     * The backend the holder blocks is the request's, the only one waiting on this owner's rows. A
     * request that finished first, its own wait run out, fails here instead of waiting for the timeout.
     */
    private void endTheConnectionWaitingFor(Connection holder, Future<?> request) throws SQLException {
        int holderPid;
        try (var statement = holder.createStatement(); var result = statement.executeQuery("select pg_backend_pid()")) {
            result.next();
            holderPid = result.getInt(1);
        }
        Optional<Integer> waiting = Optional.empty();
        while (waiting.isEmpty() && !request.isDone()) {
            waiting = jdbc.sql("select pid from pg_stat_activity where :holder = any(pg_blocking_pids(pid))")
                    .param("holder", holderPid)
                    .query(Integer.class)
                    .list().stream().findFirst();
        }
        assertThat(waiting).as("the request waiting for the holder").isPresent();
        assertThat(jdbc.sql("select pg_terminate_backend(:pid)").param("pid", waiting.get()).query(Boolean.class).single())
                .isTrue();
    }

    private static void openWalletOf(OwnerId owner, Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "insert into wallet (id, owner_id, balance, created_at) values (?, ?, 0, now())")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, owner.value());
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static void lockWalletOf(OwnerId owner, Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("select id from wallet where owner_id = ? for update")) {
            statement.setString(1, owner.value());
            assertThat(statement.executeQuery().next()).isTrue();
        }
    }

    private MvcTestResult deposit(OwnerId owner, String idempotencyKey) {
        return mvc.post().uri("/wallets/me/deposits").with(jwt().jwt(token -> token.subject(owner.value())))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": \"10.00\"}").exchange();
    }
}
