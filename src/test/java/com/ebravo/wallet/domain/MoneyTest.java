package com.ebravo.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    private static final String LARGEST = "99999999999999999.99";

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
        assertThat(money(amount).amount().toPlainString()).isEqualTo(expected);
    }

    @Test
    void amountsThatDifferOnlyInScaleAreEqual() {
        assertThat(money("5")).isEqualTo(money("5.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.999", "10.000"})
    void rejectsMoreThanTwoDecimalPlaces(String amount) {
        assertThatExceptionOfType(InvalidAmountException.class)
                .isThrownBy(() -> money(amount))
                .withMessage("Amount must have at most 2 decimal places: " + amount);
    }

    @ParameterizedTest
    @ValueSource(strings = {LARGEST, "-" + LARGEST})
    void acceptsTheLargestStorableMagnitude(String amount) {
        assertThat(money(amount).amount().toPlainString()).isEqualTo(amount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"100000000000000000.00", "-100000000000000000.00"})
    void rejectsOneCentBeyondTheLargestStorableMagnitude(String amount) {
        assertThatExceptionOfType(InvalidAmountException.class)
                .isThrownBy(() -> money(amount))
                .withMessage("Amount must not exceed %s in magnitude: %s".formatted(LARGEST, amount));
    }

    @Test
    void maxIsTheLargestStorableAmount() {
        assertThat(Money.MAX).isEqualTo(money(LARGEST));
    }

    @Test
    void rejectsMissingAmount() {
        assertThatNullPointerException().isThrownBy(() -> new Money(null)).withMessage("amount");
    }

    @Test
    void adds() {
        assertThat(money("10.25").add(money("0.75"))).isEqualTo(money("11.00"));
    }

    @Test
    void subtracts() {
        assertThat(money("10.25").subtract(money("10.50"))).isEqualTo(money("-0.25"));
    }

    @Test
    void negates() {
        assertThat(money("10.25").negate()).isEqualTo(money("-10.25"));
        assertThat(money("-10.25").negate()).isEqualTo(money("10.25"));
    }

    @ParameterizedTest
    @CsvSource({"0.01, true", "0.00, false", "-0.01, false"})
    void tellsWhetherItIsPositive(String amount, boolean positive) {
        assertThat(money(amount).isPositive()).isEqualTo(positive);
    }

    @ParameterizedTest
    @CsvSource({"10.01, 10.00, true", "10.00, 10.00, false", "9.99, 10.00, false"})
    void comparesByValue(String amount, String other, boolean greater) {
        assertThat(money(amount).isGreaterThan(money(other))).isEqualTo(greater);
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount));
    }
}
