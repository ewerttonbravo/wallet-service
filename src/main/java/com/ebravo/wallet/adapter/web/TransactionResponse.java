package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.TransactionType;
import com.ebravo.wallet.domain.WalletTransaction;
import java.time.Instant;
import java.util.UUID;

/** The amount is signed: negative when money left the wallet. Amounts are decimal strings. */
record TransactionResponse(
        UUID id, TransactionType type, String amount, String balance, String currency, String correlationId, Instant createdAt) {

    static TransactionResponse of(WalletTransaction transaction) {
        return new TransactionResponse(
                transaction.id().value(),
                transaction.type(),
                transaction.amount().amount().toPlainString(),
                transaction.balance().amount().toPlainString(),
                Money.CURRENCY.getCurrencyCode(),
                transaction.correlationId().value(),
                transaction.createdAt());
    }
}
