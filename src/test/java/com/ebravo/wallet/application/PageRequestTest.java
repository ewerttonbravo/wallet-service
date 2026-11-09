package com.ebravo.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PageRequestTest {

    @Test
    void theFirstPageIsNumberZero() {
        assertThat(new PageRequest(0, 20).offset()).isZero();
    }

    @Test
    void refusesANegativePage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PageRequest(-1, 20))
                .withMessage("page must be a whole number from 0");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void acceptsASizeFromOneToAHundred(int size) {
        assertThat(new PageRequest(0, size).size()).isEqualTo(size);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void refusesASizeOutsideOneToAHundred(int size) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PageRequest(0, size))
                .withMessage("size must be a whole number from 1 to 100");
    }

    @Test
    void skipsEveryTransactionOfTheEarlierPages() {
        assertThat(new PageRequest(3, 20).offset()).isEqualTo(60);
    }

    // In int arithmetic the offset of the last page would wrap around to a negative number.
    @Test
    void reachesTheOffsetOfTheLastPossiblePage() {
        assertThat(new PageRequest(Integer.MAX_VALUE, 100).offset()).isEqualTo(214_748_364_700L);
    }

    @Test
    void theNextPageKeepsTheSize() {
        assertThat(new PageRequest(2, 30).next()).isEqualTo(new PageRequest(3, 30));
    }
}
