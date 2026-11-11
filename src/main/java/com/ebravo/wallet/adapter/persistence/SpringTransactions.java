package com.ebravo.wallet.adapter.persistence;

import com.ebravo.wallet.application.Transactions;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every posting, wallet opening and read runs within a time budget the database keeps: the transaction
 * sets PostgreSQL's {@code transaction_timeout} first, and the server ends it once the budget runs
 * out, whether a statement is running, waiting for a lock, or the service is between statements.
 * The wallet lock's own timeout covers that lock alone, while a posting can also wait on other locks
 * (a migration's) or a slow database, and a transfer waits for two wallet locks. A per-statement
 * limit derived from the time left would not do: JDBC takes whole seconds, and Hibernate rounds the
 * time left down to none at all below half a second.
 */
@Component
class SpringTransactions implements Transactions {

    static final Duration BUDGET = Duration.ofSeconds(3);
    /**
     * A read holds no wallet lock, and its cost grows with the wallet's history: the oldest balance of
     * a wallet of a million entries takes about four seconds on a cold cache.
     */
    static final Duration READ_BUDGET = Duration.ofSeconds(5);

    // PostgreSQL's code for a transaction it ended at its transaction_timeout.
    private static final String TRANSACTION_TIMEOUT = "25P04";

    private final TransactionTemplate template;
    private final TransactionTemplate readTemplate;
    private final JdbcClient jdbc;
    private final String budgetStatement;
    private final String readBudgetStatement;

    @Autowired
    SpringTransactions(PlatformTransactionManager transactionManager, JdbcClient jdbc) {
        this(transactionManager, jdbc, BUDGET, READ_BUDGET);
    }

    SpringTransactions(
            PlatformTransactionManager transactionManager, JdbcClient jdbc, Duration budget, Duration readBudget) {
        this.template = new TransactionTemplate(transactionManager);
        this.readTemplate = new TransactionTemplate(transactionManager);
        // The database refuses a write in it, and Hibernate flushes nothing.
        this.readTemplate.setReadOnly(true);
        this.jdbc = jdbc;
        this.budgetStatement = budgetStatement(budget);
        this.readBudgetStatement = budgetStatement(readBudget);
    }

    // SET takes no parameters; the value is the service's own, never a client's.
    private static String budgetStatement(Duration budget) {
        return "set local transaction_timeout = " + budget.toMillis();
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return run(template, budgetStatement, work);
    }

    @Override
    public <T> T inReadTransaction(Supplier<T> work) {
        return run(readTemplate, readBudgetStatement, work);
    }

    /**
     * When the work fails and the rollback fails after it, the work's failure is the one thrown, with
     * the rollback's suppressed: a rollback fails on a connection that is gone, which committed
     * nothing, and the work's failure is the one that says why. The template would throw the
     * rollback's, a "connection is closed" that tells neither the client nor the operator anything.
     */
    private <T> T run(TransactionTemplate template, String budgetStatement, Supplier<T> work) {
        var failure = new AtomicReference<RuntimeException>();
        try {
            return template.execute(_ -> {
                try {
                    jdbc.sql(budgetStatement).update();
                    return work.get();
                } catch (RuntimeException exception) {
                    failure.set(exception);
                    throw exception;
                }
            });
        } catch (RuntimeException exception) {
            var cause = failure.get();
            if (cause == null || cause == exception) {
                throw pastTheBudget(exception);
            }
            cause.addSuppressed(exception);
            throw pastTheBudget(cause);
        }
    }

    /**
     * The server ends a transaction past its budget together with its connection, and neither
     * Hibernate nor Spring knows the code it sends: each reports a generic database error, which
     * would answer as a failure of the service. The code, wherever it sits in the failure, tells it.
     */
    private static RuntimeException pastTheBudget(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && TRANSACTION_TIMEOUT.equals(sql.getSQLState())) {
                return new TransactionTimedOutException("The transaction ran past its budget", failure);
            }
        }
        return failure;
    }
}
