package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.BalanceLimitExceededException;
import com.ebravo.wallet.domain.InsufficientFundsException;
import com.ebravo.wallet.domain.InvalidAmountException;
import com.ebravo.wallet.domain.RecipientNotFoundException;
import com.ebravo.wallet.domain.SameWalletTransferException;
import com.ebravo.wallet.domain.TransactionNotFoundException;
import com.ebravo.wallet.domain.WalletAlreadyExistsException;
import com.ebravo.wallet.domain.WalletException;
import com.ebravo.wallet.domain.WalletNotFoundException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps business errors, and a wallet too busy to lock, to Problem Details (RFC 9457). */
@RestControllerAdvice
class WalletProblemHandler {

    @ExceptionHandler
    ProblemDetail handle(WalletException exception) {
        var status = switch (exception) {
            case BalanceLimitExceededException _ -> HttpStatus.UNPROCESSABLE_CONTENT;
            case InsufficientFundsException _ -> HttpStatus.UNPROCESSABLE_CONTENT;
            case InvalidAmountException _ -> HttpStatus.UNPROCESSABLE_CONTENT;
            case RecipientNotFoundException _ -> HttpStatus.UNPROCESSABLE_CONTENT;
            case SameWalletTransferException _ -> HttpStatus.UNPROCESSABLE_CONTENT;
            case TransactionNotFoundException _ -> HttpStatus.NOT_FOUND;
            case WalletAlreadyExistsException _ -> HttpStatus.CONFLICT;
            case WalletNotFoundException _ -> HttpStatus.NOT_FOUND;
        };
        return ProblemDetail.forStatusAndDetail(status, exception.getMessage());
    }

    /**
     * The wallet stayed locked by other operations longer than the lock timeout. The transaction was
     * rolled back and nothing was posted, so retrying is safe.
     */
    @ExceptionHandler
    ResponseEntity<ProblemDetail> handle(CannotAcquireLockException exception) {
        var status = HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ProblemDetail.forStatusAndDetail(status, "The wallet is busy, retry the request"));
    }
}
