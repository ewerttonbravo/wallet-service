package com.ebravo.wallet.application;

class InMemoryWalletRepositoryTest implements WalletRepositoryContract {

    private final WalletRepository repository = new InMemoryWalletRepository();

    @Override
    public WalletRepository repository() {
        return repository;
    }

    @Override
    public Transactions transactions() {
        return new DirectTransactions();
    }
}
