package com.ebravo.wallet.adapter.persistence;

import static com.ebravo.wallet.Fixtures.NOW;
import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.application.Ledger;
import com.ebravo.wallet.application.LedgerContract;
import com.ebravo.wallet.application.Transactions;
import com.ebravo.wallet.application.WalletRepository;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.Wallet;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

@IntegrationTest
class JdbcLedgerTest implements LedgerContract {

    @Autowired
    private Ledger ledger;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private Transactions transactions;

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

    // Entries written one by one in autocommit would leave a half-written transaction visible.
    @Test
    void refusesToAppendOutsideATransaction() {
        var wallet = Wallet.open(anOwner(), NOW);
        wallets.add(wallet);
        var deposit = wallet.deposit(new Money(new BigDecimal("1.00")), CORRELATION, NOW).transaction();

        assertThatExceptionOfType(IllegalTransactionStateException.class).isThrownBy(() -> ledger.append(deposit));
    }
}
