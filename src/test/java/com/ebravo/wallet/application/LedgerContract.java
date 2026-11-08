package com.ebravo.wallet.application;

import static com.ebravo.wallet.Fixtures.NOW;
import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;

import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.LedgerTransaction;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.Wallet;
import java.math.BigDecimal;
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
        var withdrawal = deposit.wallet().withdraw(money("0.50"), new CorrelationId("request-2"), NOW.plusSeconds(1)).transaction();

        append(deposit.transaction());
        append(withdrawal);

        var seen = ledger().find(wallet.id(), withdrawal.id()).orElseThrow();
        assertThat(seen.amount()).isEqualTo(money("-0.50"));
        assertThat(seen.balance()).isEqualTo(money("10.00"));
        assertThat(seen).isEqualTo(withdrawal.viewFor(wallet.id()).orElseThrow());
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
