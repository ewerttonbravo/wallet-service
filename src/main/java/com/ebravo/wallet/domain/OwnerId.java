package com.ebravo.wallet.domain;

/** The user who owns a wallet, as identified by the identity provider. */
public record OwnerId(String value) {

    public OwnerId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Owner id must not be blank");
        }
    }
}
