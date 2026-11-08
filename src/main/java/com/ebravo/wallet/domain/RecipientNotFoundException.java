package com.ebravo.wallet.domain;

public final class RecipientNotFoundException extends WalletException {

    public RecipientNotFoundException() {
        super("The recipient wallet does not exist");
    }
}
