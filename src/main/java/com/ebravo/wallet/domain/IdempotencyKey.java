package com.ebravo.wallet.domain;

import java.util.regex.Pattern;

/** Names one posting a wallet's owner asked for, so that a retry of the request gets it back. */
public record IdempotencyKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    public IdempotencyKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 64 letters, digits, '.', '_', ':' or '-'");
        }
    }
}
