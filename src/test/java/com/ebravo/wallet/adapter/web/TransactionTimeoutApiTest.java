package com.ebravo.wallet.adapter.web;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.domain.OwnerId;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A use case waits on something the wallet lock's timeout does not bound, held here by another
 * transaction: it gives up at its three-second budget, rolls back, and the same request succeeds once
 * the holder lets go. The time taken shows the budget ended the wait, not an earlier limit such as
 * the two-second lock wait; the detail tells it from a busy wallet and an unavailable database.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class TransactionTimeoutApiTest {

    private static final String DETAIL = "The request took too long, retry the request";
    private static final Duration AT_LEAST = Duration.ofMillis(2500);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private DataSource dataSource;

    /**
     * Opening a wallet waits for another opening of the same owner to end, with no lock query, so no
     * lock timeout applies.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void answersServiceUnavailableWhenAnOpeningWaitsPastTheBudget() throws SQLException {
        var owner = anOwner();

        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            openWalletOf(owner, holder);

            assertTookTooLong(() -> mvc.post().uri("/wallets").with(tokenOf(owner)).exchange());
            holder.rollback();
        }

        assertThat(mvc.post().uri("/wallets").with(tokenOf(owner))).hasStatus(HttpStatus.CREATED);
    }

    /**
     * The deposit holds its wallet's lock and waits to append to the ledger, locked as a migration
     * would lock it; the lock timeout applies to the wallet's lock alone. Once the table is released,
     * the same request, key included, posts once: the one that timed out posted nothing.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void answersServiceUnavailableWhenAPostingWaitsPastTheBudget(CapturedOutput output) throws SQLException {
        var owner = anOwner();
        var key = UUID.randomUUID().toString();
        var correlationId = "timeout-" + UUID.randomUUID();
        assertThat(mvc.post().uri("/wallets").with(tokenOf(owner))).hasStatus(HttpStatus.CREATED);

        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var statement = holder.createStatement()) {
                statement.execute("lock table ledger_entry in share mode");
            }

            assertTookTooLong(() -> deposit(owner, key, correlationId));
            holder.rollback();
        }

        assertThat(output.getOut().lines().filter(line -> line.contains(correlationId)))
                .anyMatch(line -> line.contains("\"level\":\"ERROR\"") && line.contains("The request took too long"));
        assertThat(deposit(owner, key, UUID.randomUUID().toString())).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.balance").isEqualTo("10.00");
    }

    private static void assertTookTooLong(Supplier<MvcTestResult> request) {
        var start = System.nanoTime();
        var response = request.get();
        var took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response)
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasHeader(HttpHeaders.RETRY_AFTER, "1")
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.detail").isEqualTo(DETAIL);
        assertThat(took).isGreaterThanOrEqualTo(AT_LEAST);
    }

    private static void openWalletOf(OwnerId owner, Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "insert into wallet (id, owner_id, balance, created_at) values (?, ?, 0, now())")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, owner.value());
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private MvcTestResult deposit(OwnerId owner, String idempotencyKey, String correlationId) {
        return mvc.post().uri("/wallets/me/deposits").with(tokenOf(owner))
                .header("Idempotency-Key", idempotencyKey).header("X-Correlation-Id", correlationId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": \"10.00\"}").exchange();
    }

    private static RequestPostProcessor tokenOf(OwnerId owner) {
        return jwt().jwt(token -> token.subject(owner.value()));
    }
}
