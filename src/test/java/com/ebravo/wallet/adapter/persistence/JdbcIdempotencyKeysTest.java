package com.ebravo.wallet.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.application.IdempotencyKeys;
import com.ebravo.wallet.application.IdempotencyKeysContract;
import com.ebravo.wallet.application.Ledger;
import com.ebravo.wallet.application.Transactions;
import com.ebravo.wallet.application.WalletRepository;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.WalletId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

@IntegrationTest
class JdbcIdempotencyKeysTest implements IdempotencyKeysContract {

    @Autowired
    private IdempotencyKeys keys;

    @Autowired
    private Ledger ledger;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private Transactions transactions;

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
        return transactions;
    }

    // Outside the transaction, and the wallet lock, two retries could both miss the key and both post.
    @Test
    void refusesToRunOutsideATransaction() {
        var wallet = WalletId.newId();

        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> keys.find(wallet, KEY));
        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> keys.record(wallet, KEY, TransactionId.newId()));
    }
}
