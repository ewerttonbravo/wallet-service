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
        amount.requirePositive();
        if (amount.isGreaterThan(Money.MAX.subtract(balance))) {
            throw BalanceLimitExceededException.ofTheWallet();
        }
        var after = balance.add(amount);
        return post(TransactionType.DEPOSIT, after, correlationId, at,
                new ExternalEntry(amount.negate()), new WalletEntry(id, amount, after));
    }

    /** Money leaves the wallet to be settled outside the service; overdraft is not allowed. */
    public Posting withdraw(Money amount, CorrelationId correlationId, Instant at) {
        amount.requirePositive();
        if (amount.isGreaterThan(balance)) {
            throw InsufficientFundsException.forWithdrawal();
        }
        var after = balance.subtract(amount);
        return post(TransactionType.WITHDRAWAL, after, correlationId, at,
                new WalletEntry(id, amount.negate(), after), new ExternalEntry(amount));
    }

    /** Money moves to another wallet of the service: both balances change in one transaction. */
    public Posting transfer(Money amount, Wallet recipient, CorrelationId correlationId, Instant at) {
        amount.requirePositive();
        if (recipient.id.equals(id)) {
            throw new SameWalletTransferException();
        }
        if (amount.isGreaterThan(balance)) {
            throw InsufficientFundsException.forTransfer();
        }
        if (amount.isGreaterThan(Money.MAX.subtract(recipient.balance))) {
            throw BalanceLimitExceededException.ofTheRecipient();
        }
        var after = balance.subtract(amount);
        var recipientAfter = recipient.balance.add(amount);
        var transaction = new LedgerTransaction(TransactionId.newId(), TransactionType.TRANSFER,
                List.of(new WalletEntry(id, amount.negate(), after), new WalletEntry(recipient.id, amount, recipientAfter)),
                correlationId, at);
        return new Posting(List.of(withBalance(after), recipient.withBalance(recipientAfter)), transaction);
    }

    /** A wallet has a balance from the instant it was opened until now, both included. */
    public void requireBalanceKnownAt(Instant at, Instant now) {
        if (at.isAfter(now)) {
            throw InstantOutOfRangeException.inTheFuture();
        }
        if (at.isBefore(createdAt)) {
            throw InstantOutOfRangeException.beforeTheWallet();
        }
    }

    private Posting post(TransactionType type, Money after, CorrelationId correlationId, Instant at, LedgerEntry... entries) {
        var transaction = new LedgerTransaction(TransactionId.newId(), type, List.of(entries), correlationId, at);
        return new Posting(List.of(withBalance(after)), transaction);
    }

    private Wallet withBalance(Money newBalance) {
        return new Wallet(id, owner, newBalance, createdAt);
    }
}
