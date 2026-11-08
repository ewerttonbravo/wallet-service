package com.ebravo.wallet.application;

import com.ebravo.wallet.domain.OwnerId;
import com.ebravo.wallet.domain.Wallet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryWalletRepository implements WalletRepository {

    private final Map<OwnerId, Wallet> walletsByOwner = new ConcurrentHashMap<>();

    @Override
    public boolean add(Wallet wallet) {
        return walletsByOwner.putIfAbsent(wallet.owner(), wallet) == null;
    }

    @Override
    public Optional<Wallet> findByOwner(OwnerId owner) {
        return Optional.ofNullable(walletsByOwner.get(owner));
    }

    @Override
    public Optional<Wallet> lockByOwner(OwnerId owner) {
        return findByOwner(owner);
    }

    @Override
    public void updateBalance(Wallet wallet) {
        var updated = walletsByOwner.computeIfPresent(wallet.owner(), (_, stored) ->
                new Wallet(stored.id(), stored.owner(), wallet.balance(), stored.createdAt()));
        if (updated == null) {
            throw new IllegalStateException("No wallet %s to update".formatted(wallet.id().value()));
        }
    }
}
