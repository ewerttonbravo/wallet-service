package com.ebravo.wallet.adapter.persistence;

import static com.ebravo.wallet.Fixtures.NOW;
import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.application.WalletRepository;
import com.ebravo.wallet.domain.Wallet;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * The budget on the real stack, Hibernate and Spring JDBC alike. A budget of one second, the
 * smallest there is, keeps the tests short; the service's own is {@link SpringTransactions#BUDGET}.
 * One instance, so the statements can be sent through the beans it is given.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpringTransactionsTest {

    private static final int BUDGET_SECONDS = 1;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    /**
     * The wallet's lock waits up to two seconds of its own; the budget ends the wait at one, as it
     * ends a transfer's wait for its second wallet once the first one took most of the budget. The
     * failure tells which ended it: the lock's own timeout fails otherwise.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void cutsALockWaitShortAtTheBudget() throws SQLException {
        var wallet = openedWallet();

        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lock(wallet, holder);

            assertThatExceptionOfType(TransactionTimedOutException.class)
                    .isThrownBy(() -> budgeted().inTransaction(() -> wallets.lockById(wallet.id())));
            holder.rollback();
        }
    }

    /**
     * A statement started with little of the budget left still ends when the budget does: a limit
     * rounded to whole seconds would give it none at all, and the lock's own timeout would end it, or
     * one second more, and a statement timeout would end it; each fails otherwise.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void cutsAStatementStartedNearTheEndOfTheBudget() throws SQLException {
        var wallet = openedWallet();

        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lock(wallet, holder);

            assertThatExceptionOfType(TransactionTimedOutException.class)
                    .isThrownBy(() -> budgeted().inTransaction(() -> {
                        waitFor(Duration.ofMillis(600));
                        return wallets.lockById(wallet.id());
                    }));
            holder.rollback();
        }
    }

    /**
     * A transaction past its budget may lose its connection; the next ones, on the same thread that
     * the pool hands its last connection back to first, still run.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void leavesThePoolUsableAfterATimeout() {
        assertThatExceptionOfType(TransactionTimedOutException.class).isThrownBy(() -> budgeted().inTransaction(() -> {
            waitUntilTheDatabaseEndsTheTransaction();
            return jdbc.sql("select 1").query(Integer.class).single();
        }));

        for (var attempt = 0; attempt < 3; attempt++) {
            assertThat(budgeted().inTransaction(() -> jdbc.sql("select 1").query(Integer.class).single())).isEqualTo(1);
        }
    }

    /**
     * The work waits until the database has ended its transaction, then sends its statement; the
     * statement then fails, whichever way it is sent, and always as a timeout.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("statements")
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void refusesAStatementStartedPastTheBudget(String way, Supplier<Runnable> statement) {
        assertThatExceptionOfType(TransactionTimedOutException.class).isThrownBy(() -> budgeted().inTransaction(() -> {
            var prepared = statement.get();
            waitUntilTheDatabaseEndsTheTransaction();
            prepared.run();
            return null;
        }));
    }

    // The guard of the test above: the same statements run when the budget is not spent.
    @ParameterizedTest(name = "{0}")
    @MethodSource("statements")
    void runsAStatementWithinTheBudget(String way, Supplier<Runnable> statement) {
        assertThatNoException().isThrownBy(() -> budgeted().inTransaction(() -> {
            statement.get().run();
            return null;
        }));
    }

    /** Each prepares, within the budget, what its statement needs, and returns the statement. */
    Stream<Arguments> statements() {
        return Stream.of(
                Arguments.of("a repository query", (Supplier<Runnable>) () -> {
                    var wallet = openedWallet();
                    return () -> wallets.updateBalance(wallet);
                }),
                Arguments.of("a derived repository query", (Supplier<Runnable>) () -> () -> wallets.findByOwner(anOwner())),
                Arguments.of("Hibernate's own, a refresh", (Supplier<Runnable>) () -> {
                    var wallet = entityManager.find(WalletEntity.class, openedWallet().id().value());
                    return () -> entityManager.refresh(wallet);
                }),
                Arguments.of("Spring JDBC", (Supplier<Runnable>) () -> () -> jdbc.sql("select 1").query(Integer.class).single()));
    }

    private Wallet openedWallet() {
        var wallet = Wallet.open(anOwner(), NOW);
        assertThat(wallets.add(wallet)).isTrue();
        return wallet;
    }

    private SpringTransactions budgeted() {
        return new SpringTransactions(transactionManager, jdbc, Duration.ofSeconds(BUDGET_SECONDS));
    }

    /**
     * Waits until the server has ended the current transaction at its budget, watching for its backend
     * from another connection. A fixed sleep past the budget would race the server, which may end the
     * transaction later than the budget on a loaded machine; the test's {@code @Timeout} bounds a server
     * that never ends it.
     */
    private void waitUntilTheDatabaseEndsTheTransaction() {
        var backend = jdbc.sql("select pg_backend_pid()").query(Integer.class).single();
        try (var observer = dataSource.getConnection();
                var running = observer.prepareStatement("select exists (select from pg_stat_activity where pid = ?)")) {
            running.setInt(1, backend);
            while (isTrue(running)) {
                waitFor(Duration.ofMillis(10));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean isTrue(PreparedStatement query) throws SQLException {
        try (var result = query.executeQuery()) {
            return result.next() && result.getBoolean(1);
        }
    }

    private static void waitFor(Duration time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void lock(Wallet wallet, Connection holder) throws SQLException {
        try (var statement = holder.prepareStatement("select id from wallet where id = ? for update")) {
            statement.setObject(1, wallet.id().value());
            assertThat(statement.executeQuery().next()).isTrue();
        }
    }
}
