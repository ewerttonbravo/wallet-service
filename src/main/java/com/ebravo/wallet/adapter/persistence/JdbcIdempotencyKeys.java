package com.ebravo.wallet.adapter.persistence;

import com.ebravo.wallet.application.IdempotencyKeys;
import com.ebravo.wallet.domain.IdempotencyKey;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.WalletId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcIdempotencyKeys implements IdempotencyKeys {

    private final JdbcClient jdbc;

    JdbcIdempotencyKeys(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TransactionId> find(WalletId wallet, IdempotencyKey key) {
        return jdbc.sql("select transaction_id from idempotency_key where wallet_id = :wallet and key = :key")
                .param("wallet", wallet.value())
                .param("key", key.value())
                .query(UUID.class)
                .optional()
                .map(TransactionId::new);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(WalletId wallet, IdempotencyKey key, TransactionId transaction) {
        jdbc.sql("insert into idempotency_key (wallet_id, key, transaction_id) values (:wallet, :key, :transaction)")
                .param("wallet", wallet.value())
                .param("key", key.value())
                .param("transaction", transaction.value())
                .update();
    }
}
