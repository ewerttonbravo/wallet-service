package com.ebravo.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostingTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:15:30.123456Z");
    private static final Wallet WALLET = Wallet.open(new OwnerId("owner"), NOW);
    private static final LedgerTransaction DEPOSIT =
            WALLET.deposit(new Money(new BigDecimal("1.00")), new CorrelationId("request-1"), NOW).transaction();

    @Test
    void keepsItsOwnCopyOfTheWallets() {
        var wallets = new ArrayList<>(List.of(WALLET));
        var posting = new Posting(wallets, DEPOSIT);

        wallets.clear();

        assertThat(posting.wallets()).containsExactly(WALLET);
    }

    @Test
    void rejectsMissingFields() {
        assertThatNullPointerException().isThrownBy(() -> new Posting(null, DEPOSIT));
        assertThatNullPointerException().isThrownBy(() -> new Posting(List.of(WALLET), null)).withMessage("transaction");
    }
}
