package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.BalanceLimitExceededException;
import com.ebravo.wallet.domain.IdempotencyKeyReusedException;
import com.ebravo.wallet.domain.InstantOutOfRangeException;
import com.ebravo.wallet.domain.InsufficientFundsException;
import com.ebravo.wallet.domain.InvalidAmountException;
import com.ebravo.wallet.domain.RecipientNotFoundException;
import com.ebravo.wallet.domain.SameWalletTransferException;
import com.ebravo.wallet.domain.TransactionNotFoundException;
import com.ebravo.wallet.domain.WalletAlreadyExistsException;
import com.ebravo.wallet.domain.WalletException;
import com.ebravo.wallet.domain.WalletNotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps business errors, and a wallet too busy to lock, to Problem Details (RFC 9457), and counts
 * each as {@code wallet.refusals} by {@code reason}. A database that is unavailable is answered too,
 * but not counted: the service did not refuse the request.
 */
@RestControllerAdvice
class WalletProblemHandler {

    private static final Logger LOG = LoggerFactory.getLogger(WalletProblemHandler.class);
    private static final String REFUSALS = "wallet.refusals";

    private final Map<Class<?>, Counter> refusals;
    private final Counter busy;

    /** Every reason is registered at zero, so a rate over it exists before the first refusal. */
    WalletProblemHandler(MeterRegistry registry) {
        refusals = Arrays.stream(WalletException.class.getPermittedSubclasses())
                .collect(Collectors.toUnmodifiableMap(Function.identity(), error -> refusal(registry, reasonOf(error))));
        busy = refusal(registry, "wallet-busy");
    }

    @ExceptionHandler
    ProblemDetail handle(WalletException exception) {
        refusals.get(exception.getClass()).increment();
        var status = switch (exception) {
            case BalanceLimitExceededException _ -> HttpStatus.UNPROCESSABLE_CONTENT;
            case IdempotencyKeyReusedException _ -> HttpStatus.UNPROCESSABLE_CONTENT;
            case InstantOutOfRangeException _ -> HttpStatus.UNPROCESSABLE_CONTENT;
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
        busy.increment();
        return retryLater("The wallet is busy, retry the request");
    }

    /**
     * A query could not get or keep a connection, or a posting could not start its transaction: the
     * database is down, or the pool is full. Spring files a connection failure as a resource failure,
     * lasting or transient; neither tells the cases apart, the logged cause does. Retrying the same
     * request is safe; a posting carries its idempotency key, so it posts once.
     */
    @ExceptionHandler({
            DataAccessResourceFailureException.class,
            TransientDataAccessResourceException.class,
            CannotCreateTransactionException.class})
    ResponseEntity<ProblemDetail> handleDatabaseUnreachable(RuntimeException exception) {
        LOG.error("The database cannot be reached", exception);
        return retryLater("The database is unavailable, retry the request");
    }

    private static ResponseEntity<ProblemDetail> retryLater(String detail) {
        var status = HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    private static String reasonOf(Class<?> error) {
        return error.getSimpleName().replaceFirst("Exception$", "")
                .replaceAll("([a-z])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
    }

    private static Counter refusal(MeterRegistry registry, String reason) {
        return Counter.builder(REFUSALS)
                .description("Requests answered with a business error, missing resources included, or a wallet too busy to lock")
                .tag("reason", reason)
                .register(registry);
    }
}
