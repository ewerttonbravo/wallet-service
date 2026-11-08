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

    @Test
    void rejectsMissingValue() {
        assertThatNullPointerException().isThrownBy(() -> new WalletId(null)).withMessage("value");
    }
}
