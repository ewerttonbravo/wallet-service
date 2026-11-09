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

    /** Whether this is what a deposit of the amount would have posted: how a retry is told from a new request. */
    public boolean isDepositOf(Money requested) {
        return type == TransactionType.DEPOSIT && amount.equals(requested);
    }

    /** Money left the wallet, so the owner sees the withdrawal with the requested amount negated. */
    public boolean isWithdrawalOf(Money requested) {
        return type == TransactionType.WITHDRAWAL && amount.equals(requested.negate());
    }

    /** As the sender sees it: money left for the recipient, so the requested amount is negated. */
    public boolean isTransferOf(WalletId recipient, Money requested) {
        return type == TransactionType.TRANSFER
                && amount.equals(requested.negate())
                && counterparty.equals(Optional.of(recipient));
    }
}
