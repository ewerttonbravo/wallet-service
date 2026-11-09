package com.ebravo.wallet.application;

import com.ebravo.wallet.domain.LedgerTransaction;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.WalletId;
import com.ebravo.wallet.domain.WalletTransaction;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps transactions in the order they were appended, as the database's entry ids do. */
public final class InMemoryLedger implements Ledger {

    private final List<LedgerTransaction> transactions = new CopyOnWriteArrayList<>();

    @Override
    public void append(LedgerTransaction transaction) {
        transactions.add(transaction);
    }

    @Override
    public Optional<WalletTransaction> find(WalletId wallet, TransactionId id) {
        return transactions.stream()
                .filter(transaction -> transaction.id().equals(id))
                .findFirst()
                .flatMap(transaction -> transaction.viewFor(wallet));
    }

    @Override
    public TransactionPage history(WalletId wallet, PageRequest page) {
        var seen = transactions.reversed().stream().flatMap(transaction -> transaction.viewFor(wallet).stream()).toList();
        var onPage = seen.stream().skip(page.offset()).limit(page.size()).toList();
        return new TransactionPage(onPage, page, seen.size());
    }

    public boolean isEmpty() {
        return transactions.isEmpty();
    }

    public int size() {
        return transactions.size();
    }
}
