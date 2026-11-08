package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.TransactionType;
import com.ebravo.wallet.domain.WalletId;
import com.ebravo.wallet.domain.WalletTransaction;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * The amount is signed: negative when money left the wallet. Amounts are decimal strings. The
 * counterparty is the other wallet of a transfer, and is left out for deposits and withdrawals.
 */
record TransactionResponse(
        UUID id,
        TransactionType type,
        String amount,
        String balance,
        String currency,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID counterpartyWalletId,
        String correlationId,
        Instant createdAt) {

    static TransactionResponse of(WalletTransaction transaction) {
        return new TransactionResponse(
                transaction.id().value(),
                transaction.type(),
                transaction.amount().amount().toPlainString(),
                transaction.balance().amount().toPlainString(),
                Money.CURRENCY.getCurrencyCode(),
                transaction.counterparty().map(WalletId::value).orElse(null),
                transaction.correlationId().value(),
                transaction.createdAt());
    }
}
