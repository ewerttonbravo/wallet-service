package com.ebravo.wallet.application;

class InMemoryLedgerTest implements LedgerContract {

    private final Ledger ledger = new InMemoryLedger();
    private final WalletRepository wallets = new InMemoryWalletRepository();

    @Override
    public Ledger ledger() {
        return ledger;
    }

    @Override
    public WalletRepository wallets() {
        return wallets;
    }

    @Override
    public Transactions transactions() {
        return new DirectTransactions();
    }
}
