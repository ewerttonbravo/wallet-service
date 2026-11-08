package com.ebravo.wallet.domain;

import static com.ebravo.wallet.domain.TransactionType.DEPOSIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerTransactionTest {

    private static final TransactionId ID = TransactionId.newId();
    private static final WalletId WALLET = WalletId.newId();
    private static final CorrelationId CORRELATION = new CorrelationId("request-1");
    private static final Instant NOW = Instant.parse("2026-11-08T10:15:30.123456Z");

    @Test
    void acceptsEntriesThatSumToZero() {
        var entries = List.<LedgerEntry>of(new ExternalEntry(money("-10.00")), new WalletEntry(WALLET, money("10.00"), money("25.00")));

        assertThat(transaction(entries).entries()).isEqualTo(entries);
    }

    @Test
    void keepsEveryFieldAsGiven() {
        var transaction = transaction(List.of(new ExternalEntry(money("-1.00")), new WalletEntry(WALLET, money("1.00"), money("1.00"))));

        assertThat(transaction.id()).isEqualTo(ID);
        assertThat(transaction.type()).isEqualTo(DEPOSIT);
        assertThat(transaction.correlationId()).isEqualTo(CORRELATION);
        assertThat(transaction.createdAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsEntriesThatDoNotSumToZero() {
        var entries = List.<LedgerEntry>of(new ExternalEntry(money("-10.00")), new WalletEntry(WALLET, money("10.01"), money("25.01")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> transaction(entries))
                .withMessage("Ledger entries must sum to zero, got 0.01");
    }

    @Test
    void rejectsFewerThanTwoEntries() {
        var entries = List.<LedgerEntry>of(new WalletEntry(WALLET, money("10.00"), money("10.00")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> transaction(entries))
                .withMessage("A ledger transaction needs at least two entries");
    }

    @Test
    void keepsItsOwnCopyOfTheEntries() {
        var entries = new ArrayList<LedgerEntry>(List.of(new ExternalEntry(money("-1.00")), new WalletEntry(WALLET, money("1.00"), money("1.00"))));
        var transaction = transaction(entries);

        entries.clear();

        assertThat(transaction.entries()).hasSize(2);
    }

    @Test
    void showsAWalletItsOwnEntry() {
        var transaction = transaction(List.of(new ExternalEntry(money("-10.00")), new WalletEntry(WALLET, money("10.00"), money("25.00"))));

        assertThat(transaction.viewFor(WALLET))
                .contains(new WalletTransaction(ID, DEPOSIT, money("10.00"), money("25.00"), CORRELATION, NOW));
    }

    @Test
    void showsNothingToAWalletItDoesNotTouch() {
        var transaction = transaction(List.of(new ExternalEntry(money("-10.00")), new WalletEntry(WALLET, money("10.00"), money("25.00"))));

        assertThat(transaction.viewFor(WalletId.newId())).isEmpty();
    }

    @Test
    void rejectsMissingFields() {
        var entries = List.<LedgerEntry>of(new ExternalEntry(money("-1.00")), new WalletEntry(WALLET, money("1.00"), money("1.00")));

        assertThatNullPointerException().isThrownBy(() -> new LedgerTransaction(null, DEPOSIT, entries, CORRELATION, NOW)).withMessage("id");
        assertThatNullPointerException().isThrownBy(() -> new LedgerTransaction(ID, null, entries, CORRELATION, NOW)).withMessage("type");
        assertThatNullPointerException().isThrownBy(() -> new LedgerTransaction(ID, DEPOSIT, null, CORRELATION, NOW)).withMessage("entries");
        assertThatNullPointerException().isThrownBy(() -> new LedgerTransaction(ID, DEPOSIT, entries, null, NOW)).withMessage("correlationId");
        assertThatNullPointerException().isThrownBy(() -> new LedgerTransaction(ID, DEPOSIT, entries, CORRELATION, null)).withMessage("createdAt");
    }

    private static LedgerTransaction transaction(List<LedgerEntry> entries) {
        return new LedgerTransaction(ID, DEPOSIT, entries, CORRELATION, NOW);
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount));
    }
}
