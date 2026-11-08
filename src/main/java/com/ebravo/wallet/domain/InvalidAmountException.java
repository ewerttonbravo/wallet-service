package com.ebravo.wallet.domain;

public final class InvalidAmountException extends WalletException {

    public InvalidAmountException(String message) {
        super(message);
    }
}
