package com.ebravo.wallet.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebravo.wallet.domain.BalanceLimitExceededException;
import com.ebravo.wallet.domain.InsufficientFundsException;
import com.ebravo.wallet.domain.InvalidAmountException;
import com.ebravo.wallet.domain.RecipientNotFoundException;
import com.ebravo.wallet.domain.SameWalletTransferException;
import com.ebravo.wallet.domain.TransactionNotFoundException;
import com.ebravo.wallet.domain.WalletAlreadyExistsException;
import com.ebravo.wallet.domain.WalletException;
import com.ebravo.wallet.domain.WalletNotFoundException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Pins the status of every business error; API tests show them end to end. */
class WalletProblemHandlerTest {

    private final WalletProblemHandler handler = new WalletProblemHandler();

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(new BalanceLimitExceededException("The balance"), 422),
                Arguments.of(new InsufficientFundsException("withdrawal"), 422),
                Arguments.of(new InvalidAmountException("Amount must be positive"), 422),
                Arguments.of(new RecipientNotFoundException(), 422),
                Arguments.of(new SameWalletTransferException(), 422),
                Arguments.of(new TransactionNotFoundException(), 404),
                Arguments.of(new WalletAlreadyExistsException(), 409),
                Arguments.of(new WalletNotFoundException(), 404));
    }

    @ParameterizedTest
    @MethodSource("errors")
    void mapsEachBusinessErrorToItsStatusWithTheMessageAsDetail(WalletException error, int status) {
        var problem = handler.handle(error);

        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getDetail()).isEqualTo(error.getMessage());
    }
}
