package com.ebravo.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CorrelationIdTest {

    private static final String SIXTY_FOUR = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-";

    @Test
    void sixtyFourIsTheLongestAllowedLength() {
        assertThat(SIXTY_FOUR).hasSize(64);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "Req-42_x.y:z", SIXTY_FOUR})
    void keepsAValueOfOneToSixtyFourAllowedCharacters(String value) {
        assertThat(new CorrelationId(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {SIXTY_FOUR + "a", "has space", "a/b", "é", "a\nb"})
    void rejectsAnyOtherValue(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CorrelationId(value))
                .withMessage("Correlation id must be 1 to 64 letters, digits, '.', '_', ':' or '-'");
    }

    @Test
    void generatesDistinctUuids() {
        var generated = CorrelationId.generate();

        assertThat(UUID.fromString(generated.value())).isNotNull();
        assertThat(generated).isNotEqualTo(CorrelationId.generate());
    }
}
