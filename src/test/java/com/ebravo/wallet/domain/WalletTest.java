package com.ebravo.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WalletTest {

    private static final OwnerId OWNER = new OwnerId("owner");
    private static final Instant NOW = Instant.parse("2026-11-07T14:53:35.123456Z");

    @Test
    void opensWithZeroBalance() {
        var wallet = Wallet.open(OWNER, NOW);

        assertThat(wallet.id()).isNotNull();
        assertThat(wallet.owner()).isEqualTo(OWNER);
        assertThat(wallet.balance()).isEqualTo(Money.ZERO);
        assertThat(wallet.createdAt()).isEqualTo(NOW);
    }

    @Test
    void eachOpenedWalletHasItsOwnId() {
        assertThat(Wallet.open(OWNER, NOW).id()).isNotEqualTo(Wallet.open(OWNER, NOW).id());
    }

    @Test
    void rejectsMissingFields() {
        var id = WalletId.newId();

        assertThatNullPointerException().isThrownBy(() -> new Wallet(null, OWNER, Money.ZERO, NOW)).withMessage("id");
        assertThatNullPointerException().isThrownBy(() -> new Wallet(id, null, Money.ZERO, NOW)).withMessage("owner");
        assertThatNullPointerException().isThrownBy(() -> new Wallet(id, OWNER, null, NOW)).withMessage("balance");
        assertThatNullPointerException().isThrownBy(() -> new Wallet(id, OWNER, Money.ZERO, null)).withMessage("createdAt");
    }
}
