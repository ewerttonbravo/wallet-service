package com.ebravo.wallet.domain;

public final class SameWalletTransferException extends WalletException {

    public SameWalletTransferException() {
        super("A wallet cannot transfer to itself");
    }
}
