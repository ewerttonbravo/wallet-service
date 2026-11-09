package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.application.PageRequest;
import com.ebravo.wallet.domain.IdempotencyKey;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.WalletId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    // Bounds the work done on untrusted input; the largest storable amount has 20 characters.
    private static final int MAX_AMOUNT_LENGTH = 32;
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");
    // UUID.fromString also accepts shortened forms such as "1-1-1-1-1"; wallet ids are written in full.
    private static final Pattern CANONICAL_UUID = Pattern.compile("\\p{XDigit}{8}(-\\p{XDigit}{4}){3}-\\p{XDigit}{12}");
    // Ten digits hold every int; parsing decides whether the value fits.
    private static final Pattern WHOLE_NUMBER = Pattern.compile("\\d{1,10}");
    // Bounds the work done on untrusted input; the longest instant the parser accepts is shorter.
    private static final int MAX_INSTANT_LENGTH = 64;
    private static final String INSTANT_RULE = "at must be an ISO-8601 instant with offset, such as \"2026-11-09T10:00:00Z\"";
    private static final int FIRST_PAGE = 0;
    private static final int DEFAULT_PAGE_SIZE = 20;

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

    /** Checked here rather than by Spring, so a missing header gets the same Problem Details as a malformed one. */
    static IdempotencyKey idempotencyKey(String header) {
        if (header == null) {
            throw malformed(IDEMPOTENCY_KEY + " header is required");
        }
        try {
            return new IdempotencyKey(header);
        } catch (IllegalArgumentException invalid) {
            // The domain's message is fixed text: the rejected value is never echoed back.
            throw malformed(invalid.getMessage());
        }
    }

    /** The offset is required: without one, the same text names a different instant in every time zone. */
    static Instant instant(String at) {
        if (at.length() > MAX_INSTANT_LENGTH) {
            throw malformed(INSTANT_RULE);
        }
        try {
            return OffsetDateTime.parse(at, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException invalid) {
            // Its message quotes the text it could not parse.
            throw malformed(INSTANT_RULE);
        }
    }

    /** Absent parameters take the first page of {@value #DEFAULT_PAGE_SIZE}; the details never echo the value sent. */
    static PageRequest pageRequest(String page, String size) {
        var number = wholeNumber(page, FIRST_PAGE, PageRequest.PAGE_RULE);
        var length = wholeNumber(size, DEFAULT_PAGE_SIZE, PageRequest.SIZE_RULE);
        try {
            return new PageRequest(number, length);
        } catch (IllegalArgumentException outOfBounds) {
            throw malformed(outOfBounds.getMessage());
        }
    }

    private static int wholeNumber(String value, int absent, String rule) {
        if (value == null) {
            return absent;
        }
        if (!WHOLE_NUMBER.matcher(value).matches()) {
            throw malformed(rule);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException tooLarge) {
            throw malformed(rule);
        }
    }

    private static ErrorResponseException malformed(String detail) {
        return new ErrorResponseException(
                HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }
}
