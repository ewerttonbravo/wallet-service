package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.Money;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The only field a client sets when moving money. The format is checked here; whether the amount
 * is acceptable (positive, two decimals, storable) is decided by the domain.
 */
record AmountRequest(String amount) {

    // Bounds the work done on untrusted input; the largest storable amount has 20 characters.
    private static final int MAX_LENGTH = 32;
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");

    Money toMoney() {
        if (amount == null || amount.length() > MAX_LENGTH || !PLAIN_DECIMAL.matcher(amount).matches()) {
            var problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST, "amount must be a decimal string such as \"10.50\"");
            throw new ErrorResponseException(HttpStatus.BAD_REQUEST, problem, null);
        }
        return new Money(new BigDecimal(amount));
    }
}
