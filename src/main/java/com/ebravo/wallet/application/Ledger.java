package com.ebravo.wallet.application;

import com.ebravo.wallet.domain.LedgerTransaction;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.WalletId;
import com.ebravo.wallet.domain.WalletTransaction;
import java.time.Instant;
import java.util.Optional;

/** The append-only record of every movement of money. Nothing in it is ever changed or removed. */
public interface Ledger {

    /** Must run inside {@link Transactions#inTransaction}, together with the balance it explains. */
    void append(LedgerTransaction transaction);

    /** @return empty when the transaction does not exist or does not touch the wallet */
    Optional<WalletTransaction> find(WalletId wallet, TransactionId id);

    /** The wallet's transactions as it sees them, newest first: in the order they were posted, reversed. */
    TransactionPage history(WalletId wallet, PageRequest page);

    /**
     * The wallet's balance after its last entry recorded at or before the instant; of entries with
     * the same timestamp, the last posted.
     *
     * @return empty when the wallet had no entry yet
     */
    Optional<Money> balanceAt(WalletId wallet, Instant at);
}
