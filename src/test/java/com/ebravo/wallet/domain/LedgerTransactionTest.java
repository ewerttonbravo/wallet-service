package com.ebravo.wallet.domain;

import static com.ebravo.wallet.domain.TransactionType.DEPOSIT;
import static com.ebravo.wallet.domain.TransactionType.TRANSFER;
import static com.ebravo.wallet.domain.TransactionType.WITHDRAWAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LedgerTransactionTest {

    private static final TransactionId ID = TransactionId.newId();
    private static final WalletId WALLET = WalletId.newId();
    private static final WalletId OTHER = WalletId.newId();
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

    // A wallet's view of a transaction is its one entry; two would make its resulting balance ambiguous.
    @Test
    void rejectsTwoEntriesOnTheSameWallet() {
        var entries = List.<LedgerEntry>of(
                new ExternalEntry(money("-2.00")),
                new WalletEntry(WALLET, money("1.00"), money("1.00")),
                new WalletEntry(WALLET, money("1.00"), money("2.00")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> transaction(entries))
                .withMessage("A wallet can have at most one entry per ledger transaction");
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
                .contains(new WalletTransaction(ID, DEPOSIT, money("10.00"), money("25.00"), Optional.empty(), CORRELATION, NOW));
    }

    // The external account is not a wallet: a deposit names no counterparty (above), a transfer names the other wallet.
    @Test
    void showsEachSideOfATransferTheOtherWalletAsCounterparty() {
        var recipient = WalletId.newId();
        var transfer = new LedgerTransaction(ID, TRANSFER,
                List.of(new WalletEntry(WALLET, money("-10.00"), money("15.00")), new WalletEntry(recipient, money("10.00"), money("10.00"))),
                CORRELATION, NOW);

        assertThat(transfer.viewFor(WALLET)).contains(
                new WalletTransaction(ID, TRANSFER, money("-10.00"), money("15.00"), Optional.of(recipient), CORRELATION, NOW));
        assertThat(transfer.viewFor(recipient)).contains(
                new WalletTransaction(ID, TRANSFER, money("10.00"), money("10.00"), Optional.of(WALLET), CORRELATION, NOW));
    }

    @Test
    void showsNothingToAWalletItDoesNotTouch() {
        var transaction = transaction(List.of(new ExternalEntry(money("-10.00")), new WalletEntry(WALLET, money("10.00"), money("25.00"))));

        assertThat(transaction.viewFor(WalletId.newId())).isEmpty();
    }

    static Stream<Arguments> entriesThatFitTheirType() {
        return Stream.of(
                Arguments.of(DEPOSIT, List.of(new ExternalEntry(money("-1.00")), new WalletEntry(WALLET, money("1.00"), money("1.00")))),
                Arguments.of(WITHDRAWAL, List.of(new WalletEntry(WALLET, money("-1.00"), money("0.00")), new ExternalEntry(money("1.00")))),
                Arguments.of(TRANSFER, List.of(new WalletEntry(WALLET, money("-1.00"), money("0.00")), new WalletEntry(OTHER, money("1.00"), money("1.00")))));
    }

    @ParameterizedTest
    @MethodSource("entriesThatFitTheirType")
    void acceptsTheShapeOfEachType(TransactionType type, List<LedgerEntry> entries) {
        assertThat(new LedgerTransaction(ID, type, entries, CORRELATION, NOW).entries()).isEqualTo(entries);
    }

    // Each case balances to zero and breaks one condition of its type's shape (above): money created
    // or destroyed under a misleading type, or a transfer that also moves money outside the service.
    static Stream<Arguments> entriesThatDoNotFitTheirType() {
        return Stream.of(
                Arguments.of(DEPOSIT, List.of(new WalletEntry(WALLET, money("-1.00"), money("0.00")), new ExternalEntry(money("1.00")))),
                Arguments.of(DEPOSIT, List.of(new WalletEntry(WALLET, money("-1.00"), money("0.00")), new WalletEntry(OTHER, money("1.00"), money("1.00")))),
                Arguments.of(DEPOSIT, List.of(new ExternalEntry(money("-1.00")), new ExternalEntry(money("-1.00")), new WalletEntry(WALLET, money("2.00"), money("2.00")))),
                Arguments.of(WITHDRAWAL, List.of(new ExternalEntry(money("-1.00")), new WalletEntry(WALLET, money("1.00"), money("1.00")))),
                Arguments.of(WITHDRAWAL, List.of(new WalletEntry(WALLET, money("-1.00"), money("0.00")), new WalletEntry(OTHER, money("1.00"), money("1.00")))),
                Arguments.of(WITHDRAWAL, List.of(new WalletEntry(WALLET, money("-2.00"), money("0.00")), new ExternalEntry(money("1.00")), new ExternalEntry(money("1.00")))),
                Arguments.of(TRANSFER, List.of(new ExternalEntry(money("-1.00")), new WalletEntry(WALLET, money("1.00"), money("1.00")))),
                Arguments.of(TRANSFER, List.of(new WalletEntry(WALLET, money("-2.00"), money("0.00")), new WalletEntry(OTHER, money("1.00"), money("1.00")), new ExternalEntry(money("1.00")))));
    }

    @ParameterizedTest
    @MethodSource("entriesThatDoNotFitTheirType")
    void rejectsEntriesThatDoNotFitTheType(TransactionType type, List<LedgerEntry> entries) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LedgerTransaction(ID, type, entries, CORRELATION, NOW))
                .withMessage("The entries do not fit a " + type + ": a deposit or withdrawal has one wallet entry and one external entry, a transfer has two wallet entries");
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
