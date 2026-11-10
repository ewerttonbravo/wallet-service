package com.ebravo.wallet.adapter.persistence;

import com.ebravo.wallet.application.Transactions;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every use case runs within a time budget: each statement may take only the time its transaction
 * has left, and is cancelled when it runs out; the transaction then rolls back. The wallet lock's own
 * timeout covers that lock alone, while a use case can also wait on other locks (a migration's) or a
 * slow database, and a transfer waits for two wallet locks.
 */
@Component
class SpringTransactions implements Transactions {

    static final int BUDGET_SECONDS = 3;

    private final TransactionTemplate template;

    @Autowired
    SpringTransactions(PlatformTransactionManager transactionManager) {
        this(transactionManager, BUDGET_SECONDS);
    }

    SpringTransactions(PlatformTransactionManager transactionManager, int budgetSeconds) {
        template = new TransactionTemplate(transactionManager);
        template.setTimeout(budgetSeconds);
    }

    /**
     * When the work fails and the rollback fails after it, the work's failure is the one thrown, with
     * the rollback's suppressed: a rollback fails on a connection that is gone, which committed
     * nothing, and the work's failure is the one that says why. The template would throw the
     * rollback's, a "connection is closed" that tells neither the client nor the operator anything.
     */
    @Override
    public <T> T inTransaction(Supplier<T> work) {
        var failure = new AtomicReference<RuntimeException>();
        try {
            return template.execute(_ -> {
                try {
                    return work.get();
                } catch (RuntimeException exception) {
                    failure.set(exception);
                    throw exception;
                }
            });
        } catch (RuntimeException exception) {
            var cause = failure.get();
            if (cause == null || cause == exception) {
                throw exception;
            }
            cause.addSuppressed(exception);
            throw cause;
        }
    }
}
