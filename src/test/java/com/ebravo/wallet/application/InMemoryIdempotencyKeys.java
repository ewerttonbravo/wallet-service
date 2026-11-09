package com.ebravo.wallet.application;

import com.ebravo.wallet.domain.IdempotencyKey;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.WalletId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryIdempotencyKeys implements IdempotencyKeys {

    private final Map<WalletKey, TransactionId> keys = new ConcurrentHashMap<>();

    @Override
    public Optional<TransactionId> find(WalletId wallet, IdempotencyKey key) {
        return Optional.ofNullable(keys.get(new WalletKey(wallet, key)));
    }

    @Override
    public void record(WalletId wallet, IdempotencyKey key, TransactionId transaction) {
        if (keys.putIfAbsent(new WalletKey(wallet, key), transaction) != null) {
            throw new IllegalStateException("The key already names a transaction of this wallet");
        }
    }

    private record WalletKey(WalletId wallet, IdempotencyKey key) {
    }
}
