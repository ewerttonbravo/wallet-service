package com.ebravo.wallet.domain;

import java.time.Instant;

/** A ledger transaction as the owner of one of its wallets sees it. */
public record WalletTransaction(
        TransactionId id, TransactionType type, Money amount, Money balance, CorrelationId correlationId, Instant createdAt) {
}
