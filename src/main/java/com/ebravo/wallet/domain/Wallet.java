package com.ebravo.wallet.domain;

import java.time.Instant;
import java.util.Objects;

public record Wallet(WalletId id, OwnerId owner, Money balance, Instant createdAt) {

    public Wallet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static Wallet open(OwnerId owner, Instant now) {
        return new Wallet(WalletId.newId(), owner, Money.ZERO, now);
    }
}
