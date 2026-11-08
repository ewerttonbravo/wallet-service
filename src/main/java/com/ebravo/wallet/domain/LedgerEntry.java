package com.ebravo.wallet.domain;

/**
 * One side of a ledger transaction. The amount is signed: positive raises the account's balance,
 * negative lowers it.
 */
public sealed interface LedgerEntry permits WalletEntry, ExternalEntry {

    Money amount();
}
