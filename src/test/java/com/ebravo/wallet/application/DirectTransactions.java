package com.ebravo.wallet.application;

import java.util.function.Supplier;

/** Runs the work as is: the in-memory fakes have no partial writes to roll back. */
public final class DirectTransactions implements Transactions {

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return work.get();
    }
}
