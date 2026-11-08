package com.ebravo.wallet.domain;

public final class InsufficientFundsException extends WalletException {

    public InsufficientFundsException() {
        super("The balance is not enough for this withdrawal");
    }
}
