package com.ebravo.wallet.domain;

public final class WalletAlreadyExistsException extends WalletException {

    public WalletAlreadyExistsException() {
        super("The user already has a wallet");
    }
}
