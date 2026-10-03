package dev.treyer.sagapay.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable monetary amount. Never a double: money is a fixed-scale BigDecimal
 * plus an ISO-4217 currency. Every construction normalizes the scale and
 * rejects a precision that couldn't be represented without rounding.
 */
public record Money(BigDecimal amount, Currency currency) {

    /** Internal scale, aligned with the ledger's NUMERIC(20,4) column. */
    public static final int SCALE = 4;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        // RoundingMode.UNNECESSARY: reject silent precision loss instead of rounding it away.
        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public static Money zero(String currencyCode) {
        return of(BigDecimal.ZERO, currencyCode);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    public String toPlainString() {
        return amount.toPlainString();
    }

    /** For callers holding just a currency code (e.g. an account), not a full {@link Money}. */
    public boolean hasCurrencyCode(String currencyCode) {
        return currency.getCurrencyCode().equals(currencyCode);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
