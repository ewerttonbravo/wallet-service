package com.ebravo.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class TransactionIdTest {

    @Test
    void generatesDistinctIds() {
        assertThat(TransactionId.newId()).isNotEqualTo(TransactionId.newId());
    }

    @Test
    void rejectsMissingValue() {
        assertThatNullPointerException().isThrownBy(() -> new TransactionId(null)).withMessage("value");
    }
}
