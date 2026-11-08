package com.ebravo.wallet.domain;

public final class InsufficientFundsException extends WalletException {

    /** @param operation what the money was for, such as "withdrawal" */
    public InsufficientFundsException(String operation) {
        super("The balance is not enough for this " + operation);
    }
}
