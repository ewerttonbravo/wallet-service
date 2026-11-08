package com.ebravo.wallet.domain;

/**
 * A business rule refused the operation. Sealed so that every caller mapping these errors,
 * such as the HTTP layer, is forced by the compiler to handle each new one.
 */
public abstract sealed class WalletException extends RuntimeException
        permits BalanceLimitExceededException, InsufficientFundsException, InvalidAmountException,
                SameWalletTransferException, TransactionNotFoundException, WalletAlreadyExistsException,
                WalletNotFoundException {

    protected WalletException(String message) {
        super(message);
    }
}
