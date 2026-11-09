package com.ebravo.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeyTest {

    private static final String SIXTY_FOUR = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ._";

    @Test
    void sixtyFourIsTheLongestAllowedLength() {
        assertThat(SIXTY_FOUR).hasSize(64);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "8e03978e-40d5-43e8-bc93-6894a57f9324", "order:42.retry_1", SIXTY_FOUR})
    void keepsAValueOfOneToSixtyFourAllowedCharacters(String value) {
        assertThat(new IdempotencyKey(value).value()).isEqualTo(value);
    }

    // A structured-field string arrives quoted; the quotes are refused rather than taken as part of the key.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {SIXTY_FOUR + "a", "has space", "\"quoted\"", "a/b", "é", "a\nb"})
    void rejectsAnyOtherValue(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IdempotencyKey(value))
                .withMessage("Idempotency-Key must be 1 to 64 letters, digits, '.', '_', ':' or '-'");
    }
}
