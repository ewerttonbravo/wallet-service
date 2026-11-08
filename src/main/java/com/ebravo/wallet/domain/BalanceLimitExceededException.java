package com.ebravo.wallet.domain;

public final class BalanceLimitExceededException extends WalletException {

    public BalanceLimitExceededException() {
        super("The balance would exceed " + Money.MAX.amount().toPlainString());
    }
}
