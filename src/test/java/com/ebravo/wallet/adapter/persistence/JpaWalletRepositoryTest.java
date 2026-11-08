package com.ebravo.wallet.adapter.persistence;

import static com.ebravo.wallet.Fixtures.NOW;
import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.application.Transactions;
import com.ebravo.wallet.application.WalletRepository;
import com.ebravo.wallet.application.WalletRepositoryContract;
import com.ebravo.wallet.domain.Wallet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

@IntegrationTest
class JpaWalletRepositoryTest implements WalletRepositoryContract {

    @Autowired
    private WalletRepository repository;

    @Autowired
    private Transactions transactions;

    @Override
    public WalletRepository repository() {
        return repository;
    }

    @Override
    public Transactions transactions() {
        return transactions;
    }

    // A lock taken outside a transaction would be released at once and protect nothing.
    @Test
    void refusesToLockOutsideATransaction() {
        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> repository.lockByOwner(anOwner()));
    }

    // A balance changed outside the use case's transaction could be committed without its ledger entry.
    @Test
    void refusesToChangeABalanceOutsideATransaction() {
        var wallet = Wallet.open(anOwner(), NOW);
        repository.add(wallet);

        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> repository.updateBalance(wallet));
    }
}
