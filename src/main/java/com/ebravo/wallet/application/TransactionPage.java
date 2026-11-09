package com.ebravo.wallet.application;

import com.ebravo.wallet.domain.WalletTransaction;
import java.util.List;
import java.util.Optional;

/** One page of a wallet's transactions, newest first, with the number of transactions in the whole history. */
public record TransactionPage(List<WalletTransaction> transactions, PageRequest request, long total) {

    public TransactionPage {
        transactions = List.copyOf(transactions);
    }

    public long totalPages() {
        return Math.ceilDiv(total, request.size());
    }

    /** @return empty on the last page, past it, and on the last page number an int can hold */
    public Optional<PageRequest> next() {
        var hasNext = request.number() < Integer.MAX_VALUE && request.number() + 1L < totalPages();
        return hasNext ? Optional.of(request.next()) : Optional.empty();
    }
}
