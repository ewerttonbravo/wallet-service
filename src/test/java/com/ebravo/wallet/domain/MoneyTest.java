package com.ebravo.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void currencyIsBrazilianReal() {
        assertThat(Money.CURRENCY.getCurrencyCode()).isEqualTo("BRL");
    }

    @Test
    void zeroHasTwoDecimalPlaces() {
        assertThat(Money.ZERO.amount().toPlainString()).isEqualTo("0.00");
    }

    @ParameterizedTest
    @CsvSource({"5, 5.00", "5.1, 5.10", "5.12, 5.12", "-3, -3.00", "1E+3, 1000.00"})
    void normalizesToTwoDecimalPlacesWithoutRounding(String amount, String expected) {
        assertThat(new Money(new BigDecimal(amount)).amount().toPlainString()).isEqualTo(expected);
    }

    @Test
    void amountsThatDifferOnlyInScaleAreEqual() {
        assertThat(new Money(new BigDecimal("5"))).isEqualTo(new Money(new BigDecimal("5.00")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.999", "10.000"})
    void rejectsMoreThanTwoDecimalPlaces(String amount) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Money(new BigDecimal(amount)))
                .withMessage("Amount must have at most 2 decimal places: " + amount);
    }

    @Test
    void rejectsMissingAmount() {
        assertThatNullPointerException().isThrownBy(() -> new Money(null)).withMessage("amount");
    }
}
