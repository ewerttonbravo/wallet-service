package com.ebravo.wallet.domain;

/** A balance was asked for an instant the wallet has no balance at. */
public final class InstantOutOfRangeException extends WalletException {

    private InstantOutOfRangeException(String message) {
        super(message);
    }

    public static InstantOutOfRangeException beforeTheWallet() {
        return new InstantOutOfRangeException("The wallet did not exist at that instant");
    }

    public static InstantOutOfRangeException inTheFuture() {
        return new InstantOutOfRangeException("The instant is in the future");
    }
}
