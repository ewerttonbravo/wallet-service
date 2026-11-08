package com.ebravo.wallet.domain;

import java.util.Objects;

/**
 * An entry on the account that stands for money outside the service (payment providers, banks).
 * It keeps no running balance: one would be a single row that every deposit and withdrawal of every
 * user has to lock. Its balance is the sum of its entries.
 */
public record ExternalEntry(Money amount) implements LedgerEntry {

    public ExternalEntry {
        Objects.requireNonNull(amount, "amount");
    }
}
