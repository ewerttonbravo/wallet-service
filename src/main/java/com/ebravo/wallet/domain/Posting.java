package com.ebravo.wallet.domain;

import java.util.List;
import java.util.Objects;

/** The outcome of moving money: every wallet with its new balance and the transaction that records it. */
public record Posting(List<Wallet> wallets, LedgerTransaction transaction) {

    public Posting {
        wallets = List.copyOf(wallets);
        Objects.requireNonNull(transaction, "transaction");
    }
}
