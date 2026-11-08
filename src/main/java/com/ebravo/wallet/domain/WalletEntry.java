package com.ebravo.wallet.domain;

import java.util.Objects;

/** An entry on a user's wallet, with the wallet's balance right after it. */
public record WalletEntry(WalletId wallet, Money amount, Money balanceAfter) implements LedgerEntry {

    public WalletEntry {
        Objects.requireNonNull(wallet, "wallet");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
    }
}
