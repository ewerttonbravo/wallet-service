package com.ebravo.wallet.domain;

/** The key already names a posting the request does not match, such as a deposit of another amount. */
public final class IdempotencyKeyReusedException extends WalletException {

    public IdempotencyKeyReusedException() {
        super("The Idempotency-Key was already used for another request");
    }
}
