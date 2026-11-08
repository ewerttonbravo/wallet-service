package com.ebravo.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionIdTest {

    @Test
    void keepsTheValueAsGiven() {
        var value = UUID.randomUUID();

        assertThat(new TransactionId(value).value()).isEqualTo(value);
    }

    @Test
    void generatesDistinctIds() {
        assertThat(TransactionId.newId()).isNotEqualTo(TransactionId.newId());
    }

    @Test
    void rejectsMissingValue() {
        assertThatNullPointerException().isThrownBy(() -> new TransactionId(null)).withMessage("value");
    }
}
