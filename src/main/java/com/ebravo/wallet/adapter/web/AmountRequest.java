package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.Money;

/** The only field a client sets when depositing or withdrawing. */
record AmountRequest(String amount) {

    Money toMoney() {
        return RequestFields.amount(amount);
    }
}
