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
}
