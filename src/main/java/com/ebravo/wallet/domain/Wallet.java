package com.ebravo.wallet.domain;

import java.time.Instant;
import java.util.List;
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

    /** Money settled outside the service enters the wallet. */
    public Posting deposit(Money amount, CorrelationId correlationId, Instant at) {
        requirePositive(amount);
        if (amount.isGreaterThan(Money.MAX.subtract(balance))) {
            throw new BalanceLimitExceededException();
        }
        var after = balance.add(amount);
        return post(TransactionType.DEPOSIT, after, correlationId, at,
                new ExternalEntry(amount.negate()), new WalletEntry(id, amount, after));
    }

    /** Money leaves the wallet to be settled outside the service; overdraft is not allowed. */
    public Posting withdraw(Money amount, CorrelationId correlationId, Instant at) {
        requirePositive(amount);
        if (amount.isGreaterThan(balance)) {
            throw new InsufficientFundsException();
        }
        var after = balance.subtract(amount);
        return post(TransactionType.WITHDRAWAL, after, correlationId, at,
                new WalletEntry(id, amount.negate(), after), new ExternalEntry(amount));
    }

    private Posting post(TransactionType type, Money after, CorrelationId correlationId, Instant at, LedgerEntry... entries) {
        var transaction = new LedgerTransaction(TransactionId.newId(), type, List.of(entries), correlationId, at);
        return new Posting(new Wallet(id, owner, after, createdAt), transaction);
    }

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw new InvalidAmountException("Amount must be positive: " + amount.amount().toPlainString());
        }
    }
}
