package com.ebravo.wallet.domain;

public final class BalanceLimitExceededException extends WalletException {

    private BalanceLimitExceededException(String balance) {
        super(balance + " would exceed " + Money.MAX.amount().toPlainString());
    }

    public static BalanceLimitExceededException ofTheWallet() {
        return new BalanceLimitExceededException("The balance");
    }

    public static BalanceLimitExceededException ofTheRecipient() {
        return new BalanceLimitExceededException("The recipient's balance");
    }
}
