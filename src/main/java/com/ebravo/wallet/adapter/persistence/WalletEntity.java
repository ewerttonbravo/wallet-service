package com.ebravo.wallet.adapter.persistence;

import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.OwnerId;
import com.ebravo.wallet.domain.Wallet;
import com.ebravo.wallet.domain.WalletId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet")
class WalletEntity {

    @Id
    private UUID id;

    @Column(name = "owner_id", columnDefinition = "text", nullable = false, updatable = false)
    private String ownerId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WalletEntity() {
    }

    Wallet toDomain() {
        return new Wallet(new WalletId(id), new OwnerId(ownerId), new Money(balance), createdAt);
    }
}
