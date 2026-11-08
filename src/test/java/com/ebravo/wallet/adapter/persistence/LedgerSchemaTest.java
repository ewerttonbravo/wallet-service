package com.ebravo.wallet.adapter.persistence;

import static com.ebravo.wallet.Fixtures.NOW;
import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.application.WalletRepository;
import com.ebravo.wallet.domain.Wallet;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database as the last line of defense: it refuses what the code never writes. Each refusal
 * is shown next to a valid write of the same shape, so it cannot pass for an unrelated reason.
 */
@IntegrationTest
class LedgerSchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private WalletRepository wallets;

    @Test
    void acceptsABalancedTransaction() {
        var wallet = aWallet();
        var id = UUID.randomUUID();

        transaction.executeWithoutResult(_ -> {
            insertTransaction(id);
            insertEntry(id, null, "-10.00", null);
            insertEntry(id, wallet, "10.00", "10.00");
        });

        assertThat(entriesOf(id)).isEqualTo(2);
    }

    @Test
    void refusesATransactionWhoseEntriesDoNotSumToZero() {
        var wallet = aWallet();
        var id = UUID.randomUUID();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> transaction.executeWithoutResult(_ -> {
                    insertTransaction(id);
                    insertEntry(id, null, "-10.00", null);
                    insertEntry(id, wallet, "10.01", "10.01");
                }))
                .withMessageContaining("must have at least two entries summing to zero");
        assertThat(entriesOf(id)).isZero();
    }

    @Test
    void refusesATransactionWithASingleEntry() {
        var id = UUID.randomUUID();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> transaction.executeWithoutResult(_ -> insertTransaction(id)))
                .withMessageContaining("must have at least two entries summing to zero");
    }

    @Test
    void refusesAnEntryAddedLaterToABalancedTransaction() {
        var id = aBalancedTransaction();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> transaction.executeWithoutResult(_ -> insertEntry(id, null, "-1.00", null)))
                .withMessageContaining("must have at least two entries summing to zero");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update ledger_transaction set correlation_id = 'changed' where id = :id",
            "delete from ledger_transaction where id = :id",
            "update ledger_entry set amount = amount where transaction_id = :id",
            "delete from ledger_entry where transaction_id = :id"})
    void refusesToChangeOrDeleteLedgerRows(String statement) {
        var id = aBalancedTransaction();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql(statement).param("id", id).update())
                .withMessageContaining("The ledger is append-only");
        assertThat(entriesOf(id)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ledger_transaction", "ledger_entry"})
    void refusesToTruncateTheLedger(String table) {
        var id = aBalancedTransaction();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("truncate " + table + " cascade").update())
                .withMessageContaining("The ledger is append-only");
        assertThat(entriesOf(id)).isEqualTo(2);
    }

    @Test
    void refusesANegativeWalletBalance() {
        var wallet = aWallet();

        assertThat(setBalance(wallet, "0.00")).isEqualTo(1);
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> setBalance(wallet, "-0.01"))
                .withMessageContaining("ck_wallet_balance_non_negative");
    }

    @Test
    void refusesAZeroAmountEntry() {
        var wallet = aWallet();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> transaction.executeWithoutResult(_ -> {
                    var id = UUID.randomUUID();
                    insertTransaction(id);
                    insertEntry(id, null, "0.00", null);
                    insertEntry(id, wallet, "0.00", "0.00");
                }))
                .withMessageContaining("ck_ledger_entry_amount_non_zero");
    }

    @ParameterizedTest
    @ValueSource(strings = {"wallet without balance", "external with balance", "negative balance"})
    void refusesAnEntryWhoseBalanceDoesNotMatchItsAccount(String shape) {
        var wallet = aWallet();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> transaction.executeWithoutResult(_ -> {
                    var id = UUID.randomUUID();
                    insertTransaction(id);
                    switch (shape) {
                        case "wallet without balance" -> insertEntry(id, wallet, "1.00", null);
                        case "external with balance" -> insertEntry(id, null, "1.00", "1.00");
                        default -> insertEntry(id, wallet, "1.00", "-1.00");
                    }
                }))
                .withMessageContaining("ck_ledger_entry_balance");
    }

    private UUID aBalancedTransaction() {
        var wallet = aWallet();
        var id = UUID.randomUUID();
        transaction.executeWithoutResult(_ -> {
            insertTransaction(id);
            insertEntry(id, null, "-10.00", null);
            insertEntry(id, wallet, "10.00", "10.00");
        });
        return id;
    }

    private UUID aWallet() {
        var wallet = Wallet.open(anOwner(), NOW);
        wallets.add(wallet);
        return wallet.id().value();
    }

    private void insertTransaction(UUID id) {
        jdbc.sql("insert into ledger_transaction (id, type, correlation_id, created_at) values (:id, 'DEPOSIT', 'test', :at)")
                .param("id", id)
                .param("at", NOW.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertEntry(UUID transactionId, UUID wallet, String amount, String balanceAfter) {
        jdbc.sql("""
                        insert into ledger_entry (transaction_id, wallet_id, amount, balance_after)
                        values (:transactionId, cast(:wallet as uuid), :amount, cast(:balanceAfter as numeric))
                        """)
                .param("transactionId", transactionId)
                .param("wallet", wallet == null ? null : wallet.toString())
                .param("amount", new BigDecimal(amount))
                .param("balanceAfter", balanceAfter)
                .update();
    }

    private int setBalance(UUID wallet, String balance) {
        return jdbc.sql("update wallet set balance = :balance where id = :id")
                .param("balance", new BigDecimal(balance))
                .param("id", wallet)
                .update();
    }

    private int entriesOf(UUID transactionId) {
        return jdbc.sql("select count(*) from ledger_entry where transaction_id = :id")
                .param("id", transactionId)
                .query(Integer.class)
                .single();
    }
}
