package com.ebravo.wallet.adapter.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Exposes only what the adapter needs: wallets are never updated through save() nor deleted. */
interface WalletEntityRepository extends Repository<WalletEntity, UUID> {

    Optional<WalletEntity> findByOwnerId(String ownerId);

    /**
     * Native on purpose: the unique constraint settles concurrent openings atomically, and save()
     * would merge an entity with an assigned id instead of inserting it.
     *
     * @return 1 when inserted, 0 when the owner already has a wallet
     */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into wallet (id, owner_id, balance, created_at)
            values (:id, :ownerId, :balance, :createdAt)
            on conflict (owner_id) do nothing
            """)
    int insertIfOwnerHasNone(UUID id, String ownerId, BigDecimal balance, Instant createdAt);
}
