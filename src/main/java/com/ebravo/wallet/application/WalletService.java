package com.ebravo.wallet.application;

import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.OwnerId;
import com.ebravo.wallet.domain.Posting;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.TransactionNotFoundException;
import com.ebravo.wallet.domain.Wallet;
import com.ebravo.wallet.domain.WalletAlreadyExistsException;
import com.ebravo.wallet.domain.WalletNotFoundException;
import com.ebravo.wallet.domain.WalletTransaction;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.BiFunction;

public final class WalletService {

    private final WalletRepository wallets;
    private final Ledger ledger;
    private final Transactions transactions;
    private final Clock clock;

    public WalletService(WalletRepository wallets, Ledger ledger, Transactions transactions, Clock clock) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Wallet open(OwnerId owner) {
        var wallet = Wallet.open(owner, now());
        if (!wallets.add(wallet)) {
            throw new WalletAlreadyExistsException();
        }
        return wallet;
    }

    public Wallet get(OwnerId owner) {
        return wallets.findByOwner(owner).orElseThrow(WalletNotFoundException::new);
    }

    public WalletTransaction deposit(OwnerId owner, Money amount, CorrelationId correlationId) {
        // Checked before the lock too, so a request that can never succeed does not wait for it.
        amount.requirePositive();
        return post(owner, (wallet, at) -> wallet.deposit(amount, correlationId, at));
    }

    public WalletTransaction withdraw(OwnerId owner, Money amount, CorrelationId correlationId) {
        amount.requirePositive();
        return post(owner, (wallet, at) -> wallet.withdraw(amount, correlationId, at));
    }

    /** Only the owner's own transactions are found: anyone else's is reported as missing. */
    public WalletTransaction transaction(OwnerId owner, TransactionId id) {
        return ledger.find(get(owner).id(), id).orElseThrow(TransactionNotFoundException::new);
    }

    private WalletTransaction post(OwnerId owner, BiFunction<Wallet, Instant, Posting> operation) {
        return transactions.inTransaction(() -> {
            var wallet = wallets.lockByOwner(owner).orElseThrow(WalletNotFoundException::new);
            // Read under the lock, so a wallet's entries are timestamped in the order they were posted.
            var posting = operation.apply(wallet, now());
            wallets.updateBalance(posting.wallet());
            ledger.append(posting.transaction());
            return posting.transaction().viewFor(wallet.id()).orElseThrow();
        });
    }

    // The database keeps microseconds; truncating makes what is returned equal to what is stored.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
