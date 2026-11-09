package com.ebravo.wallet.application;

class InMemoryIdempotencyKeysTest implements IdempotencyKeysContract {

    private final IdempotencyKeys keys = new InMemoryIdempotencyKeys();
    private final Ledger ledger = new InMemoryLedger();
    private final WalletRepository wallets = new InMemoryWalletRepository();

    @Override
    public IdempotencyKeys keys() {
        return keys;
    }

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
