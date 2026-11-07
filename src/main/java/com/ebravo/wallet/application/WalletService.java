package com.ebravo.wallet.application;

import com.ebravo.wallet.domain.OwnerId;
import com.ebravo.wallet.domain.Wallet;
import com.ebravo.wallet.domain.WalletAlreadyExistsException;
import com.ebravo.wallet.domain.WalletNotFoundException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

public final class WalletService {

    private final WalletRepository wallets;
    private final Clock clock;

    public WalletService(WalletRepository wallets, Clock clock) {
        this.wallets = wallets;
        this.clock = clock;
    }

    public Wallet open(OwnerId owner) {
        // The database keeps microseconds; truncating makes the returned wallet equal to the stored one.
        var wallet = Wallet.open(owner, clock.instant().truncatedTo(ChronoUnit.MICROS));
        if (!wallets.add(wallet)) {
            throw new WalletAlreadyExistsException();
        }
        return wallet;
    }

    public Wallet get(OwnerId owner) {
        return wallets.findByOwner(owner).orElseThrow(WalletNotFoundException::new);
    }
}
