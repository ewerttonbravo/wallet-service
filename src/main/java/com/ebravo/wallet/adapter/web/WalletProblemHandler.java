package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.WalletAlreadyExistsException;
import com.ebravo.wallet.domain.WalletException;
import com.ebravo.wallet.domain.WalletNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps business errors to Problem Details (RFC 9457). */
@RestControllerAdvice
class WalletProblemHandler {

    @ExceptionHandler
    ProblemDetail handle(WalletException exception) {
        var status = switch (exception) {
            case WalletAlreadyExistsException _ -> HttpStatus.CONFLICT;
            case WalletNotFoundException _ -> HttpStatus.NOT_FOUND;
        };
        return ProblemDetail.forStatusAndDetail(status, exception.getMessage());
    }
}
