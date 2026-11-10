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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * The budget on the real stack, Hibernate and Spring JDBC alike. A budget of one second, the
 * smallest there is, keeps the tests short; the service's own is {@link SpringTransactions#BUDGET_SECONDS}.
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
     * ends a transfer's wait for its second wallet once the first one took most of the budget.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void cutsALockWaitShortAtTheBudget() throws SQLException {
        var wallet = Wallet.open(anOwner(), NOW);
        assertThat(wallets.add(wallet)).isTrue();

        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var statement = holder.prepareStatement("select id from wallet where id = ? for update")) {
                statement.setObject(1, wallet.id().value());
                assertThat(statement.executeQuery().next()).isTrue();
            }

            var start = System.nanoTime();
            assertThatExceptionOfType(QueryTimeoutException.class)
                    .isThrownBy(() -> budgeted().inTransaction(() -> wallets.lockById(wallet.id())));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1900));
            holder.rollback();
        }
    }

    /**
     * Time passing is the input here, so the work waits past the budget before its statement; the
     * statement is then refused before it runs, whichever way it is sent, and always as a timeout.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("statements")
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void refusesAStatementStartedPastTheBudget(String way, Supplier<Runnable> statement) {
        assertThatExceptionOfType(TransactionTimedOutException.class).isThrownBy(() -> budgeted().inTransaction(() -> {
            var prepared = statement.get();
            waitPastTheBudget();
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
        return new SpringTransactions(transactionManager, BUDGET_SECONDS);
    }

    private static void waitPastTheBudget() {
        try {
            Thread.sleep(Duration.ofSeconds(BUDGET_SECONDS).plusMillis(100));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
