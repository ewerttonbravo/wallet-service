package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.Wallet;
import java.time.Instant;
import java.util.UUID;

record WalletResponse(UUID id, String currency, Instant createdAt) {

    static WalletResponse of(Wallet wallet) {
        return new WalletResponse(wallet.id().value(), Money.CURRENCY.getCurrencyCode(), wallet.createdAt());
    }
}
