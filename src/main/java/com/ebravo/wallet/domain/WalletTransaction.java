package com.ebravo.wallet.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * A ledger transaction as the owner of one of its wallets sees it. The counterparty is the other
 * wallet of a transfer; money from or to outside the service has none.
 */
public record WalletTransaction(
        TransactionId id,
        TransactionType type,
        Money amount,
        Money balance,
        Optional<WalletId> counterparty,
        CorrelationId correlationId,
        Instant createdAt) {
}
