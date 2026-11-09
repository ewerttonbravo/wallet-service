package com.ebravo.wallet.application;

import com.ebravo.wallet.domain.IdempotencyKey;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.WalletId;
import java.util.Optional;

/**
 * The keys a wallet's owner sent with the postings they asked for. A key belongs to its wallet, so
 * the same key on another wallet names nothing. Keys are kept as long as their transactions.
 */
public interface IdempotencyKeys {

    /** Must run inside {@link Transactions#inTransaction}, under the wallet's lock. */
    Optional<TransactionId> find(WalletId wallet, IdempotencyKey key);

    /** Must run inside {@link Transactions#inTransaction}, together with the transaction it names. */
    void record(WalletId wallet, IdempotencyKey key, TransactionId transaction);
}
