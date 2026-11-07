package com.ebravo.wallet.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount in the service's single currency, always with exactly {@value #SCALE} decimal places.
 * Amounts with more decimal places are rejected instead of rounded.
 */
public record Money(BigDecimal amount) {

    public static final Currency CURRENCY = Currency.getInstance("BRL");
    public static final int SCALE = 2;
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (amount.scale() > SCALE) {
            throw new IllegalArgumentException(
                    "Amount must have at most %d decimal places: %s".formatted(SCALE, amount.toPlainString()));
        }
        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }
}
