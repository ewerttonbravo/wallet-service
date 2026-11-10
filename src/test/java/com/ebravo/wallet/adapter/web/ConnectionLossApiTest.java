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

            endTheConnectionWaitingFor(holder);

            assertThat(deposit.get())
                    .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .hasHeader(HttpHeaders.RETRY_AFTER, "1")
                    .bodyJson().extractingPath("$.detail").isEqualTo("The database is unavailable, retry the request");
            holder.rollback();
        }

        assertThat(deposit(owner, key)).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.balance").isEqualTo("10.00");
    }

    /** The backend blocked by the holder's lock is the deposit's; no other test waits on this wallet. */
    private void endTheConnectionWaitingFor(Connection holder) throws SQLException {
        int holderPid;
        try (var statement = holder.createStatement(); var result = statement.executeQuery("select pg_backend_pid()")) {
            result.next();
            holderPid = result.getInt(1);
        }
        Optional<Integer> waiting = Optional.empty();
        while (waiting.isEmpty()) {
            waiting = jdbc.sql("select pid from pg_stat_activity where :holder = any(pg_blocking_pids(pid))")
                    .param("holder", holderPid)
                    .query(Integer.class)
                    .optional();
        }
        assertThat(jdbc.sql("select pg_terminate_backend(:pid)").param("pid", waiting.get()).query(Boolean.class).single())
                .isTrue();
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
