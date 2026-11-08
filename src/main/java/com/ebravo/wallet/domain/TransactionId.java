package com.ebravo.wallet.domain;

import java.util.Objects;
import java.util.UUID;

public record TransactionId(UUID value) {

    public TransactionId {
        Objects.requireNonNull(value, "value");
    }

    public static TransactionId newId() {
        return new TransactionId(UUID.randomUUID());
    }
}
