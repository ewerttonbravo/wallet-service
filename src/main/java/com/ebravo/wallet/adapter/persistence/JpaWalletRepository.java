package com.ebravo.wallet.adapter.persistence;

import com.ebravo.wallet.application.WalletRepository;
import com.ebravo.wallet.domain.OwnerId;
import com.ebravo.wallet.domain.Wallet;
import com.ebravo.wallet.domain.WalletId;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class JpaWalletRepository implements WalletRepository {

    private final WalletEntityRepository jpa;
    private final EntityManager entityManager;

    JpaWalletRepository(WalletEntityRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
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
        return jpa.lockByOwnerId(owner.value()).map(this::readUnderLock);
    }

    @Override
    public Optional<Wallet> lockById(WalletId id) {
        return jpa.lockById(id.value()).map(this::readUnderLock);
    }

    /**
     * A wallet read earlier in the transaction stays in the persistence context, and a locking query
     * hands back that instance as it was read, not as it is stored now. Refreshing it once the row is
     * locked reads the balance the lock protects.
     */
    private Wallet readUnderLock(WalletEntity wallet) {
        entityManager.refresh(wallet);
        return wallet.toDomain();
    }

    @Override
    public void updateBalance(Wallet wallet) {
        if (jpa.updateBalance(wallet.id().value(), wallet.balance().amount()) != 1) {
            throw new IllegalStateException("No wallet %s to update".formatted(wallet.id().value()));
        }
    }
}
