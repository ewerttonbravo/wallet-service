package com.ebravo.wallet.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/** Ties a ledger transaction to the request that caused it, across the systems it went through. */
public record CorrelationId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    public CorrelationId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Correlation id must be 1 to 64 letters, digits, '.', '_', ':' or '-'");
        }
    }

    public static CorrelationId generate() {
        return new CorrelationId(UUID.randomUUID().toString());
    }
}
