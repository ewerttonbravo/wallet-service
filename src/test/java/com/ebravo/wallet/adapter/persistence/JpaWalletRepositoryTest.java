package com.ebravo.wallet.adapter.persistence;

import static com.ebravo.wallet.Fixtures.NOW;
import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.application.Transactions;
import com.ebravo.wallet.application.WalletRepository;
import com.ebravo.wallet.application.WalletRepositoryContract;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.Wallet;
import com.ebravo.wallet.domain.WalletId;
import java.math.BigDecimal;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

@IntegrationTest
class JpaWalletRepositoryTest implements WalletRepositoryContract {

    @Autowired
    private WalletRepository repository;

    @Autowired
    private Transactions transactions;

    @Autowired
    private DataSource dataSource;

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

    @Test
    void refusesToLockByIdOutsideATransaction() {
        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> repository.lockById(WalletId.newId()));
    }

    /**
     * The wallet was read earlier in the same transaction, and another transaction then changed its
     * balance and committed. The lock must hand back the balance it protects, not the earlier read:
     * a stale one would be overwritten by the next posting.
     */
    @ParameterizedTest
    @ValueSource(strings = {"by owner", "by id"})
    void locksTheBalanceAsStoredNotAsReadEarlierInTheTransaction(String lock) {
        var wallet = Wallet.open(anOwner(), NOW);
        repository.add(wallet);

        var locked = transactions.inTransaction(() -> {
            assertThat(repository.findByOwner(wallet.owner())).map(Wallet::balance).contains(Money.ZERO);
            setBalanceElsewhere(wallet, "12.34");
            return lock.equals("by id") ? repository.lockById(wallet.id()) : repository.lockByOwner(wallet.owner());
        });

        assertThat(locked).map(Wallet::balance).contains(new Money(new BigDecimal("12.34")));
    }

    /** Commits on a connection of its own, outside the transaction under test. */
    private void setBalanceElsewhere(Wallet wallet, String balance) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("update wallet set balance = ? where id = ?")) {
            connection.setAutoCommit(true);
            statement.setBigDecimal(1, new BigDecimal(balance));
            statement.setObject(2, wallet.id().value());
            assertThat(statement.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
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
