package com.ebravo.wallet.adapter.persistence;

import static com.ebravo.wallet.Fixtures.NOW;
import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.application.Ledger;
import com.ebravo.wallet.application.WalletRepository;
import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.Wallet;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database refuses idempotency keys the code never writes. Each refusal follows a valid key
 * of the same shape, so it cannot pass for an unrelated reason.
 */
@IntegrationTest
class IdempotencyKeySchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private Ledger ledger;

    @Test
    void refusesASecondTransactionForTheSameKeyOfAWallet() {
        var wallet = aWallet();
        insertKey(wallet.id().value(), "key-1", aDepositInto(wallet));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertKey(wallet.id().value(), "key-1", aDepositInto(wallet)))
                .withMessageContaining("pk_idempotency_key");
        assertThat(keysOf(wallet)).isEqualTo(1);
    }

    @Test
    void refusesASecondKeyForTheSameTransaction() {
        var wallet = aWallet();
        var deposit = aDepositInto(wallet);
        insertKey(wallet.id().value(), "key-1", deposit);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertKey(wallet.id().value(), "key-2", deposit))
                .withMessageContaining("uk_idempotency_key_transaction");
        assertThat(keysOf(wallet)).isEqualTo(1);
    }

    @Test
    void refusesAKeyNamingATransactionThatDoesNotTouchItsWallet() {
        var wallet = aWallet();
        var stranger = aWallet();
        var deposit = aDepositInto(wallet);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertKey(stranger.id().value(), "key-1", deposit))
                .withMessageContaining("fk_idempotency_key_entry");
        insertKey(wallet.id().value(), "key-1", deposit);
        assertThat(keysOf(wallet)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update idempotency_key set key = 'changed' where wallet_id = :wallet",
            "delete from idempotency_key where wallet_id = :wallet"})
    void refusesToChangeOrDeleteAKey(String statement) {
        var wallet = aWallet();
        insertKey(wallet.id().value(), "key-1", aDepositInto(wallet));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql(statement).param("wallet", wallet.id().value()).update())
                .withMessageContaining("The ledger is append-only");
        assertThat(keysOf(wallet)).isEqualTo(1);
    }

    @Test
    void refusesToTruncateTheKeys() {
        var wallet = aWallet();
        insertKey(wallet.id().value(), "key-1", aDepositInto(wallet));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("truncate idempotency_key").update())
                .withMessageContaining("The ledger is append-only");
        assertThat(keysOf(wallet)).isEqualTo(1);
    }

    private Wallet aWallet() {
        var wallet = Wallet.open(anOwner(), NOW);
        wallets.add(wallet);
        return wallet;
    }

    private UUID aDepositInto(Wallet wallet) {
        var deposit = wallet.deposit(new Money(new BigDecimal("10.00")), new CorrelationId("schema-test"), NOW).transaction();
        transaction.executeWithoutResult(_ -> ledger.append(deposit));
        return deposit.id().value();
    }

    private void insertKey(UUID wallet, String key, UUID transactionId) {
        jdbc.sql("insert into idempotency_key (wallet_id, key, transaction_id) values (:wallet, :key, :transaction)")
                .param("wallet", wallet)
                .param("key", key)
                .param("transaction", transactionId)
                .update();
    }

    private int keysOf(Wallet wallet) {
        return jdbc.sql("select count(*) from idempotency_key where wallet_id = :wallet")
                .param("wallet", wallet.id().value())
                .query(Integer.class)
                .single();
    }
}
