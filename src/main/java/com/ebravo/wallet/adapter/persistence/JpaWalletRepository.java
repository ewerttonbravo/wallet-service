package com.ebravo.wallet.adapter.persistence;

import com.ebravo.wallet.application.WalletRepository;
import com.ebravo.wallet.domain.OwnerId;
import com.ebravo.wallet.domain.Wallet;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class JpaWalletRepository implements WalletRepository {

    private final WalletEntityRepository jpa;

    JpaWalletRepository(WalletEntityRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean add(Wallet wallet) {
        return jpa.insertIfOwnerHasNone(
                wallet.id().value(), wallet.owner().value(), wallet.balance().amount(), wallet.createdAt()) == 1;
    }

    @Override
    public Optional<Wallet> findByOwner(OwnerId owner) {
        return jpa.findByOwnerId(owner.value()).map(WalletEntity::toDomain);
    }

    @Override
    public Optional<Wallet> lockByOwner(OwnerId owner) {
        return jpa.lockByOwnerId(owner.value()).map(WalletEntity::toDomain);
    }

    @Override
    public void updateBalance(Wallet wallet) {
        if (jpa.updateBalance(wallet.id().value(), wallet.balance().amount()) != 1) {
            throw new IllegalStateException("No wallet %s to update".formatted(wallet.id().value()));
        }
    }
}
