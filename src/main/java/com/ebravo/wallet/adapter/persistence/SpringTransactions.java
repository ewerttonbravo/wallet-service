package com.ebravo.wallet.adapter.persistence;

import com.ebravo.wallet.application.Transactions;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class SpringTransactions implements Transactions {

    private final TransactionTemplate template;

    SpringTransactions(TransactionTemplate template) {
        this.template = template;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(_ -> work.get());
    }
}
