package com.ebravo.wallet.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount in the service's single currency, always with exactly {@value #SCALE} decimal places.
 * Amounts with more decimal places are rejected instead of rounded, and so are amounts too large to
 * be stored ({@code numeric(19, 2)}).
 */
public record Money(BigDecimal amount) {

    public static final Currency CURRENCY = Currency.getInstance("BRL");
    public static final int SCALE = 2;
    public static final Money ZERO = new Money(BigDecimal.ZERO);
    public static final Money MAX = new Money(new BigDecimal("99999999999999999.99"));

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (amount.scale() > SCALE) {
            throw new InvalidAmountException(
                    "Amount must have at most %d decimal places: %s".formatted(SCALE, amount.toPlainString()));
        }
        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        // MAX is null while it is being built itself, and it is within the bound.
        if (MAX != null && amount.abs().compareTo(MAX.amount) > 0) {
            throw new InvalidAmountException("Amount must not exceed %s in magnitude: %s"
                    .formatted(MAX.amount.toPlainString(), amount.toPlainString()));
        }
    }

    public Money add(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money subtract(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money negate() {
        return new Money(amount.negate());
    }

    /** Money moved by an operation must be above zero. */
    public void requirePositive() {
        if (!isPositive()) {
            throw new InvalidAmountException("Amount must be positive: " + amount.toPlainString());
        }
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isGreaterThan(Money other) {
        return amount.compareTo(other.amount) > 0;
    }
}
