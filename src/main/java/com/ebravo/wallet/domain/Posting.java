package com.ebravo.wallet.domain;

/** The outcome of moving money: the wallet with its new balance and the transaction that records it. */
public record Posting(Wallet wallet, LedgerTransaction transaction) {
}
