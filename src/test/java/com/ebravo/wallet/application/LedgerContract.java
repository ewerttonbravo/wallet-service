package com.ebravo.wallet.application;

import static com.ebravo.wallet.Fixtures.NOW;
import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;

import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.LedgerTransaction;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.Wallet;
import com.ebravo.wallet.domain.WalletTransaction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behavior every {@link Ledger} must have, run against each implementation. */
public interface LedgerContract {

    CorrelationId CORRELATION = new CorrelationId("request-1");

    Ledger ledger();

    WalletRepository wallets();

    Transactions transactions();

    @Test
    default void findsAnAppendedTransactionAsItsWalletSeesIt() {
        var wallet = aWallet();
        var deposit = wallet.deposit(money("10.50"), CORRELATION, NOW).transaction();

        append(deposit);

        assertThat(ledger().find(wallet.id(), deposit.id())).contains(deposit.viewFor(wallet.id()).orElseThrow());
    }

    @Test
    default void keepsTheSignedAmountAndResultingBalanceOfAWithdrawal() {
        var wallet = aWallet();
        var deposit = wallet.deposit(money("10.50"), CORRELATION, NOW);
        var withdrawal = deposit.wallets().getFirst().withdraw(money("0.50"), new CorrelationId("request-2"), NOW.plusSeconds(1)).transaction();

        append(deposit.transaction());
        append(withdrawal);

        var seen = ledger().find(wallet.id(), withdrawal.id()).orElseThrow();
        assertThat(seen.amount()).isEqualTo(money("-0.50"));
        assertThat(seen.balance()).isEqualTo(money("10.00"));
        assertThat(seen).isEqualTo(withdrawal.viewFor(wallet.id()).orElseThrow());
    }

    @Test
    default void findsATransferFromEitherSideWithTheOtherWalletAsCounterparty() {
        var sender = aWallet();
        var recipient = aWallet();
        var deposit = sender.deposit(money("10.50"), CORRELATION, NOW);
        var transfer = deposit.wallets().getFirst()
                .transfer(money("4.00"), recipient, new CorrelationId("request-2"), NOW.plusSeconds(1)).transaction();

        append(deposit.transaction());
        append(transfer);

        var sent = ledger().find(sender.id(), transfer.id()).orElseThrow();
        assertThat(sent.amount()).isEqualTo(money("-4.00"));
        assertThat(sent.counterparty()).contains(recipient.id());
        assertThat(ledger().find(recipient.id(), transfer.id())).contains(transfer.viewFor(recipient.id()).orElseThrow());
        assertThat(ledger().find(sender.id(), deposit.transaction().id()).orElseThrow().counterparty()).isEmpty();
    }

    // The same transaction is found by its own wallet, so the other wallet is refused for not owning it.
    @Test
    default void hidesATransactionFromAWalletItDoesNotTouch() {
        var wallet = aWallet();
        var stranger = aWallet();
        var deposit = wallet.deposit(money("10.50"), CORRELATION, NOW).transaction();

        append(deposit);

        assertThat(ledger().find(wallet.id(), deposit.id())).isPresent();
        assertThat(ledger().find(stranger.id(), deposit.id())).isEmpty();
    }

    @Test
    default void findsNothingForAnUnknownTransaction() {
        assertThat(ledger().find(aWallet().id(), TransactionId.newId())).isEmpty();
    }

    // The first append is kept as it was: the second neither replaces it nor lists it twice.
    @Test
    default void refusesToAppendATransactionTwice() {
        var wallet = aWallet();
        var deposit = wallet.deposit(money("10.50"), CORRELATION, NOW).transaction();
        append(deposit);

        assertThatRuntimeException().isThrownBy(() -> append(deposit));
        assertThat(ledger().history(wallet.id(), new PageRequest(0, 20)).transactions())
                .containsExactly(viewOf(deposit, wallet));
    }

    @Test
    default void listsAWalletsTransactionsNewestFirstAsItSeesThem() {
        var wallet = aWallet();
        var postings = postings(wallet, 3);

        var page = ledger().history(wallet.id(), new PageRequest(0, 20));

        assertThat(page.transactions()).containsExactly(
                viewOf(postings.get(2), wallet), viewOf(postings.get(1), wallet), viewOf(postings.get(0), wallet));
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.request()).isEqualTo(new PageRequest(0, 20));
    }

    @Test
    default void listsATransferOnBothWalletsEachWithItsOwnView() {
        var sender = aWallet();
        var recipient = aWallet();
        var deposit = sender.deposit(money("10.50"), CORRELATION, NOW);
        var transfer = deposit.wallets().getFirst()
                .transfer(money("4.00"), recipient, new CorrelationId("request-2"), NOW.plusSeconds(1)).transaction();
        append(deposit.transaction());
        append(transfer);

        assertThat(ledger().history(sender.id(), new PageRequest(0, 20)).transactions())
                .containsExactly(viewOf(transfer, sender), viewOf(deposit.transaction(), sender));
        assertThat(ledger().history(recipient.id(), new PageRequest(0, 20)).transactions())
                .containsExactly(viewOf(transfer, recipient));
    }

    // The stranger's own listing shows its transactions exist, so their absence here is not a broken query.
    @Test
    default void listsAndCountsOnlyTheWalletsOwnTransactions() {
        var wallet = aWallet();
        var stranger = aWallet();
        var own = postings(wallet, 1);
        var theirs = postings(stranger, 2);

        var page = ledger().history(wallet.id(), new PageRequest(0, 20));

        assertThat(page.transactions()).containsExactly(viewOf(own.getFirst(), wallet));
        assertThat(page.total()).isEqualTo(1);
        assertThat(ledger().history(stranger.id(), new PageRequest(0, 20)).transactions())
                .containsExactly(viewOf(theirs.get(1), stranger), viewOf(theirs.get(0), stranger));
    }

    @Test
    default void pagesThroughTheHistoryWithoutGapsOrRepeats() {
        var wallet = aWallet();
        var postings = postings(wallet, 5);

        var first = ledger().history(wallet.id(), new PageRequest(0, 2));
        var second = ledger().history(wallet.id(), new PageRequest(1, 2));
        var last = ledger().history(wallet.id(), new PageRequest(2, 2));

        assertThat(first.transactions()).containsExactly(viewOf(postings.get(4), wallet), viewOf(postings.get(3), wallet));
        assertThat(second.transactions()).containsExactly(viewOf(postings.get(2), wallet), viewOf(postings.get(1), wallet));
        assertThat(last.transactions()).containsExactly(viewOf(postings.get(0), wallet));
        assertThat(List.of(first.total(), second.total(), last.total())).containsOnly(5L);
    }

    @Test
    default void answersAPagePastTheEndWithNoTransactionsButTheTotal() {
        var wallet = aWallet();
        postings(wallet, 2);

        var page = ledger().history(wallet.id(), new PageRequest(1, 2));

        assertThat(page.transactions()).isEmpty();
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    default void listsNothingForAWalletWithoutTransactions() {
        var page = ledger().history(aWallet().id(), new PageRequest(0, 20));

        assertThat(page.transactions()).isEmpty();
        assertThat(page.total()).isZero();
    }

    /** Deposits of 1.00, 2.00, … appended oldest first, each a second after the previous one. */
    private List<LedgerTransaction> postings(Wallet wallet, int count) {
        var posted = new ArrayList<LedgerTransaction>();
        var current = wallet;
        for (var i = 1; i <= count; i++) {
            var deposit = current.deposit(money(i + ".00"), CORRELATION, NOW.plusSeconds(i));
            append(deposit.transaction());
            posted.add(deposit.transaction());
            current = deposit.wallets().getFirst();
        }
        return posted;
    }

    private static WalletTransaction viewOf(LedgerTransaction transaction, Wallet wallet) {
        return transaction.viewFor(wallet.id()).orElseThrow();
    }

    private Wallet aWallet() {
        var wallet = Wallet.open(anOwner(), NOW);
        wallets().add(wallet);
        return wallet;
    }

    private void append(LedgerTransaction transaction) {
        transactions().inTransaction(() -> {
            ledger().append(transaction);
            return null;
        });
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount));
    }
}
