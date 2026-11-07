package com.ebravo.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class WalletIdTest {

    @Test
    void generatesDistinctIds() {
        assertThat(WalletId.newId()).isNotEqualTo(WalletId.newId());
    }

    @Test
    void rejectsMissingValue() {
        assertThatNullPointerException().isThrownBy(() -> new WalletId(null)).withMessage("value");
    }
}
