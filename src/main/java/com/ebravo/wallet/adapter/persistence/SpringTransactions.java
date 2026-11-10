package com.ebravo.wallet.adapter.persistence;

import com.ebravo.wallet.application.Transactions;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class SpringTransactions implements Transactions {

    private final TransactionTemplate template;

    SpringTransactions(TransactionTemplate template) {
        this.template = template;
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
