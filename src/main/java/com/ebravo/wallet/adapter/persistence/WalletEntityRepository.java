package com.ebravo.wallet.adapter.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Exposes only what the adapter needs: wallets are never written through save() nor deleted; only their balance changes. */
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

    /**
     * {@code select … for update}: the row stays locked until the surrounding transaction ends. A
     * wallet held longer than the timeout by other operations fails fast instead of holding a thread
     * and a connection.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000"))
    @Query("select w from WalletEntity w where w.ownerId = :ownerId")
    Optional<WalletEntity> lockByOwnerId(String ownerId);

    /** Same lock and timeout as {@link #lockByOwnerId}. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000"))
    @Query("select w from WalletEntity w where w.id = :id")
    Optional<WalletEntity> lockById(UUID id);

    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(nativeQuery = true, value = "update wallet set balance = :balance where id = :id")
    int updateBalance(UUID id, BigDecimal balance);
}
