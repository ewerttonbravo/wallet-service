package com.ebravo.wallet.domain;

public final class WalletNotFoundException extends WalletException {

    public WalletNotFoundException() {
        super("The user has no wallet");
    }
}
