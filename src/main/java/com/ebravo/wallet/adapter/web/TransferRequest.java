package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.WalletId;

/** The only fields a client sets when transferring: the sender is always the caller. */
record TransferRequest(String recipientWalletId, String amount) {

    WalletId recipient() {
        return RequestFields.walletId("recipientWalletId", recipientWalletId);
    }

    Money toMoney() {
        return RequestFields.amount(amount);
    }
}
