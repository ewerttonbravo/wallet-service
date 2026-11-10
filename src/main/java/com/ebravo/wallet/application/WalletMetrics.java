package com.ebravo.wallet.application;

import com.ebravo.wallet.domain.TransactionType;

/** Counts what the use cases did, once it committed. Never amounts or identities: only how many. */
public interface WalletMetrics {

    void posted(TransactionType type);

    /** A retry answered with the transaction its key already posted, which moved no money. */
    void replayed(TransactionType type);
}
