package com.ebravo.wallet.application;

import com.ebravo.wallet.domain.OwnerId;
import com.ebravo.wallet.domain.Wallet;
import java.util.Optional;

public interface WalletRepository {

    /**
     * Stores the wallet unless its owner already has one, atomically even under concurrent calls.
     *
     * @return {@code false} when the owner already has a wallet; nothing is stored in that case
     */
    boolean add(Wallet wallet);

    Optional<Wallet> findByOwner(OwnerId owner);

    /**
     * Finds the wallet and locks it until the current transaction ends, so concurrent operations on
     * it run one after the other. Must run inside {@link Transactions#inTransaction}.
     */
    Optional<Wallet> lockByOwner(OwnerId owner);

    /**
     * Stores the wallet's new balance. Must run inside {@link Transactions#inTransaction}.
     *
     * @throws IllegalStateException when no such wallet is stored
     */
    void updateBalance(Wallet wallet);
}
