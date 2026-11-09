package com.ebravo.wallet.domain;

public final class InsufficientFundsException extends WalletException {

    private InsufficientFundsException(String operation) {
        super("The balance is not enough for this " + operation);
    }

    public static InsufficientFundsException forWithdrawal() {
        return new InsufficientFundsException("withdrawal");
    }

    public static InsufficientFundsException forTransfer() {
        return new InsufficientFundsException("transfer");
    }
}
