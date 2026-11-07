package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.Money;

/** The amount is a decimal string: JSON numbers are parsed as binary floating point by many clients. */
record BalanceResponse(String balance, String currency) {

    static BalanceResponse of(Money money) {
        return new BalanceResponse(money.amount().toPlainString(), Money.CURRENCY.getCurrencyCode());
    }
}
