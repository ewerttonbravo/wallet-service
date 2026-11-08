package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.WalletId;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Format checks on untrusted request fields: what is not well-formed is refused with 400 here.
 * Whether a well-formed value is acceptable is decided by the domain.
 */
final class RequestFields {

    // Bounds the work done on untrusted input; the largest storable amount has 20 characters.
    private static final int MAX_AMOUNT_LENGTH = 32;
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");
    // UUID.fromString also accepts shortened forms such as "1-1-1-1-1"; wallet ids are written in full.
    private static final Pattern CANONICAL_UUID = Pattern.compile("\\p{XDigit}{8}(-\\p{XDigit}{4}){3}-\\p{XDigit}{12}");

    private RequestFields() {
    }

    static Money amount(String amount) {
        if (amount == null || amount.length() > MAX_AMOUNT_LENGTH || !PLAIN_DECIMAL.matcher(amount).matches()) {
            throw malformed("amount must be a decimal string such as \"10.50\"");
        }
        return new Money(new BigDecimal(amount));
    }

    static WalletId walletId(String field, String value) {
        if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
            throw malformed(field + " must be a wallet id such as \"0b6a9c7e-3f1d-4e2a-9c5b-7d8e1f2a3b4c\"");
        }
        return new WalletId(UUID.fromString(value));
    }

    private static ErrorResponseException malformed(String detail) {
        return new ErrorResponseException(
                HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }
}
