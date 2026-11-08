package com.ebravo.wallet.domain;

public final class TransactionNotFoundException extends WalletException {

    public TransactionNotFoundException() {
        super("The user has no such transaction");
    }
}
