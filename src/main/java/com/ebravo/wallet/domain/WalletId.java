package com.ebravo.wallet.domain;

import java.util.Objects;
import java.util.UUID;

/** Ordered so that operations locking several wallets always take the locks in the same order. */
public record WalletId(UUID value) implements Comparable<WalletId> {

    public WalletId {
        Objects.requireNonNull(value, "value");
    }

    public static WalletId newId() {
        return new WalletId(UUID.randomUUID());
    }

    @Override
    public int compareTo(WalletId other) {
        return value.compareTo(other.value);
    }
}
