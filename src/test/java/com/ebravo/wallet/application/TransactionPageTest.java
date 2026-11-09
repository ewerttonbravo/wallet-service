package com.ebravo.wallet.application;

import static com.ebravo.wallet.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.TransactionType;
import com.ebravo.wallet.domain.WalletTransaction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TransactionPageTest {

    @ParameterizedTest
    @CsvSource({"0, 0", "1, 1", "20, 1", "21, 2", "40, 2", "41, 3"})
    void countsAPartlyFilledLastPageAsAPage(long total, long pages) {
        assertThat(page(0, 20, total).totalPages()).isEqualTo(pages);
    }

    @Test
    void linksTheNextPageBeforeTheLast() {
        assertThat(page(0, 20, 21).next()).contains(new PageRequest(1, 20));
    }

    @Test
    void theLastPageHasNoNext() {
        assertThat(page(1, 20, 21).next()).isEmpty();
        assertThat(page(0, 20, 20).next()).isEmpty();
    }

    @Test
    void aPagePastTheEndHasNoNext() {
        assertThat(page(5, 20, 21).next()).isEmpty();
    }

    @Test
    void anEmptyHistoryHasNoNext() {
        assertThat(page(0, 20, 0).next()).isEmpty();
    }

    @Test
    void keepsTheTransactionsItWasGiven() {
        var given = new ArrayList<>(List.of(aDeposit()));
        var page = new TransactionPage(given, new PageRequest(0, 20), 1);

        given.clear();

        assertThat(page.transactions()).hasSize(1);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> page.transactions().add(aDeposit()));
    }

    private static TransactionPage page(int number, int size, long total) {
        return new TransactionPage(List.of(), new PageRequest(number, size), total);
    }

    private static WalletTransaction aDeposit() {
        var amount = new Money(new BigDecimal("1.00"));
        return new WalletTransaction(TransactionId.newId(), TransactionType.DEPOSIT, amount, amount, Optional.empty(),
                new CorrelationId("request-1"), NOW);
    }
}
