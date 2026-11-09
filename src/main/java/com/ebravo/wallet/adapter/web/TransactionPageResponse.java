package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.application.PageRequest;
import com.ebravo.wallet.application.TransactionPage;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Each transaction as reading it on its own shows it. {@code next} is the path of the next page, left out on the last. */
record TransactionPageResponse(
        List<TransactionResponse> transactions,
        int page,
        int size,
        long totalElements,
        long totalPages,
        @JsonInclude(JsonInclude.Include.NON_NULL) String next) {

    static TransactionPageResponse of(TransactionPage page) {
        return new TransactionPageResponse(
                page.transactions().stream().map(TransactionResponse::of).toList(),
                page.request().number(),
                page.request().size(),
                page.total(),
                page.totalPages(),
                page.next().map(TransactionPageResponse::path).orElse(null));
    }

    private static String path(PageRequest request) {
        return "/wallets/me/transactions?page=" + request.number() + "&size=" + request.size();
    }
}
