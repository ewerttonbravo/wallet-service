package com.ebravo.wallet.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** An operation as recorded in the double-entry ledger: its entries always sum to zero. */
public record LedgerTransaction(
        TransactionId id, TransactionType type, List<LedgerEntry> entries, CorrelationId correlationId, Instant createdAt) {

    public LedgerTransaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(createdAt, "createdAt");
        entries = List.copyOf(entries);
        if (entries.size() < 2) {
            throw new IllegalArgumentException("A ledger transaction needs at least two entries");
        }
        if (walletEntries(entries).count() != walletEntries(entries).map(WalletEntry::wallet).distinct().count()) {
            throw new IllegalArgumentException("A wallet can have at most one entry per ledger transaction");
        }
        var sum = entries.stream().map(LedgerEntry::amount).reduce(Money.ZERO, Money::add);
        if (!sum.equals(Money.ZERO)) {
            throw new IllegalArgumentException("Ledger entries must sum to zero, got " + sum.amount().toPlainString());
        }
    }

    public Optional<WalletTransaction> viewFor(WalletId wallet) {
        return walletEntries(entries)
                .filter(own -> own.wallet().equals(wallet))
                .findFirst()
                .map(own -> new WalletTransaction(id, type, own.amount(), own.balanceAfter(), correlationId, createdAt));
    }

    private static Stream<WalletEntry> walletEntries(List<LedgerEntry> entries) {
        return entries.stream().filter(WalletEntry.class::isInstance).map(WalletEntry.class::cast);
    }
}
