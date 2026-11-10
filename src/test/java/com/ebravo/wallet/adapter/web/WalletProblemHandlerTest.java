package com.ebravo.wallet.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;

/** Pins the status of every business error; API tests show them end to end. */
class WalletProblemHandlerTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final WalletProblemHandler handler = new WalletProblemHandler(registry);

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(BalanceLimitExceededException.ofTheWallet(), 422, "balance-limit-exceeded"),
                Arguments.of(new IdempotencyKeyReusedException(), 422, "idempotency-key-reused"),
                Arguments.of(InstantOutOfRangeException.beforeTheWallet(), 422, "instant-out-of-range"),
                Arguments.of(InsufficientFundsException.forWithdrawal(), 422, "insufficient-funds"),
                Arguments.of(new InvalidAmountException("Amount must be positive"), 422, "invalid-amount"),
                Arguments.of(new RecipientNotFoundException(), 422, "recipient-not-found"),
                Arguments.of(new SameWalletTransferException(), 422, "same-wallet-transfer"),
                Arguments.of(new TransactionNotFoundException(), 404, "transaction-not-found"),
                Arguments.of(new WalletAlreadyExistsException(), 409, "wallet-already-exists"),
                Arguments.of(new WalletNotFoundException(), 404, "wallet-not-found"));
    }

    @ParameterizedTest
    @MethodSource("errors")
    void mapsEachBusinessErrorToItsStatusWithTheMessageAsDetail(WalletException error, int status, String reason) {
        var problem = handler.handle(error);

        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getDetail()).isEqualTo(error.getMessage());
    }

    @ParameterizedTest
    @MethodSource("errors")
    void countsEachRefusalByItsReason(WalletException error, int status, String reason) {
        handler.handle(error);

        assertThat(refusals(reason)).isEqualTo(1);
        assertThat(registry.find("wallet.refusals").counters()).filteredOn(it -> it.count() > 0).hasSize(1);
    }

    @Test
    void countsABusyWallet() {
        handler.handle(new CannotAcquireLockException("lock timeout"));

        assertThat(refusals("wallet-busy")).isEqualTo(1);
    }

    /** Every reason exists from the start, at zero, so a rate over it exists before the first refusal. */
    @Test
    void registersEveryReasonAtZero() {
        assertThat(registry.find("wallet.refusals").counters())
                .hasSize(errors().count() + 1)
                .allMatch(counter -> counter.count() == 0);
    }

    private double refusals(String reason) {
        return registry.get("wallet.refusals").tag("reason", reason).counter().count();
    }
}
