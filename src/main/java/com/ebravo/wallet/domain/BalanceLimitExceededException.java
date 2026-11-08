package com.ebravo.wallet.domain;

public final class BalanceLimitExceededException extends WalletException {

    /** @param balance whose balance it is, such as "The recipient's balance" */
    public BalanceLimitExceededException(String balance) {
        super(balance + " would exceed " + Money.MAX.amount().toPlainString());
    }
}
