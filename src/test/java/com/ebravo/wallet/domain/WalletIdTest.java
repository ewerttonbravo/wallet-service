package com.ebravo.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class WalletIdTest {

    @Test
    void keepsTheValueAsGiven() {
        var value = UUID.randomUUID();

        assertThat(new WalletId(value).value()).isEqualTo(value);
    }

    @Test
    void generatesDistinctIds() {
        assertThat(WalletId.newId()).isNotEqualTo(WalletId.newId());
    }

    // Transfers lock their two wallets in this order, so it must be total and the same in both directions.
    @Test
    void ordersWalletsByTheirUuid() {
        var lower = new WalletId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        var higher = new WalletId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

        assertThat(lower).isLessThan(higher);
        assertThat(higher).isGreaterThan(lower);
        assertThat(lower.compareTo(new WalletId(lower.value()))).isZero();
    }

    @Test
    void rejectsMissingValue() {
        assertThatNullPointerException().isThrownBy(() -> new WalletId(null)).withMessage("value");
    }
}
