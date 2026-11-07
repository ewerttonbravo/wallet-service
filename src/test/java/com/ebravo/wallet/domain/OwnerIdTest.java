package com.ebravo.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OwnerIdTest {

    @Test
    void keepsTheValueAsGiven() {
        assertThat(new OwnerId("auth0|5f2b").value()).isEqualTo("auth0|5f2b");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsBlankValues(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OwnerId(value))
                .withMessage("Owner id must not be blank");
    }
}
