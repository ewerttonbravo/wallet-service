package com.ebravo.wallet.application;

import com.ebravo.wallet.domain.LedgerTransaction;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.WalletId;
import com.ebravo.wallet.domain.WalletTransaction;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryLedger implements Ledger {

    private final Map<TransactionId, LedgerTransaction> transactions = new ConcurrentHashMap<>();

    @Override
    public void append(LedgerTransaction transaction) {
        transactions.put(transaction.id(), transaction);
    }

    @Override
    public Optional<WalletTransaction> find(WalletId wallet, TransactionId id) {
        return Optional.ofNullable(transactions.get(id)).flatMap(transaction -> transaction.viewFor(wallet));
    }

    public boolean isEmpty() {
        return transactions.isEmpty();
    }
}
