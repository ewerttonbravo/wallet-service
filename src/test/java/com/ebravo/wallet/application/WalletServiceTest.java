package com.ebravo.wallet.application;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.InsufficientFundsException;
import com.ebravo.wallet.domain.InvalidAmountException;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.TransactionNotFoundException;
import com.ebravo.wallet.domain.TransactionType;
import com.ebravo.wallet.domain.WalletAlreadyExistsException;
import com.ebravo.wallet.domain.WalletNotFoundException;
import com.ebravo.wallet.domain.WalletTransaction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class WalletServiceTest {

    private static final Instant NOW_IN_NANOS = Instant.parse("2026-11-07T14:53:35.123456789Z");
    private static final Instant NOW_IN_MICROS = Instant.parse("2026-11-07T14:53:35.123456Z");
    private static final CorrelationId CORRELATION = new CorrelationId("request-1");

    private final WalletRepository wallets = new InMemoryWalletRepository();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final CountingTransactions transactions = new CountingTransactions();
    private final WalletService service =
            new WalletService(wallets, ledger, transactions, Clock.fixed(NOW_IN_NANOS, ZoneOffset.UTC));

    @Test
    void opensAWalletWithZeroBalanceForTheOwner() {
        var owner = anOwner();

        var wallet = service.open(owner);

        assertThat(wallet.owner()).isEqualTo(owner);
        assertThat(wallet.balance()).isEqualTo(Money.ZERO);
        assertThat(wallets.findByOwner(owner)).contains(wallet);
    }

    @Test
    void recordsTheOpeningInstantAtMicrosecondPrecision() {
        assertThat(service.open(anOwner()).createdAt()).isEqualTo(NOW_IN_MICROS);
    }

    @Test
    void refusesASecondWalletForTheSameOwner() {
        var owner = anOwner();
        var first = service.open(owner);

        assertThatExceptionOfType(WalletAlreadyExistsException.class)
                .isThrownBy(() -> service.open(owner))
                .withMessage("The user already has a wallet");
        assertThat(wallets.findByOwner(owner)).contains(first);
    }

    @Test
    void findsTheWalletOfTheOwner() {
        var owner = anOwner();
        var wallet = service.open(owner);

        assertThat(service.get(owner)).isEqualTo(wallet);
    }

    @Test
    void reportsAnOwnerWithoutWallet() {
        assertThatExceptionOfType(WalletNotFoundException.class)
                .isThrownBy(() -> service.get(anOwner()))
                .withMessage("The user has no wallet");
    }

    @Test
    void depositsIntoTheWalletOfTheOwner() {
        var owner = anOwner();
        var wallet = service.open(owner);

        var deposit = service.deposit(owner, money("10.50"), CORRELATION);

        assertThat(deposit).usingRecursiveComparison().ignoringFields("id").isEqualTo(new WalletTransaction(
                TransactionId.newId(), TransactionType.DEPOSIT, money("10.50"), money("10.50"), CORRELATION, NOW_IN_MICROS));
        assertThat(service.get(owner).balance()).isEqualTo(money("10.50"));
        assertThat(ledger.find(wallet.id(), deposit.id())).contains(deposit);
    }

    @Test
    void withdrawsFromTheWalletOfTheOwner() {
        var owner = anOwner();
        var wallet = service.open(owner);
        service.deposit(owner, money("10.50"), CORRELATION);

        var withdrawal = service.withdraw(owner, money("0.50"), new CorrelationId("request-2"));

        assertThat(withdrawal).usingRecursiveComparison().ignoringFields("id").isEqualTo(new WalletTransaction(
                TransactionId.newId(), TransactionType.WITHDRAWAL, money("-0.50"), money("10.00"),
                new CorrelationId("request-2"), NOW_IN_MICROS));
        assertThat(service.get(owner).balance()).isEqualTo(money("10.00"));
        assertThat(ledger.find(wallet.id(), withdrawal.id())).contains(withdrawal);
    }

    @Test
    void eachPostingRunsInOneTransaction() {
        var owner = anOwner();
        service.open(owner);

        service.deposit(owner, money("10.50"), CORRELATION);
        service.withdraw(owner, money("0.50"), CORRELATION);

        assertThat(transactions.count).isEqualTo(2);
    }

    // Valid postings open one transaction each (above); a hopeless one must not even take the lock.
    @Test
    void refusesANonPositiveAmountBeforeTouchingTheWallet() {
        var owner = anOwner();
        service.open(owner);

        assertThatExceptionOfType(InvalidAmountException.class)
                .isThrownBy(() -> service.deposit(owner, Money.ZERO, CORRELATION));
        assertThatExceptionOfType(InvalidAmountException.class)
                .isThrownBy(() -> service.withdraw(owner, money("-0.01"), CORRELATION));
        assertThat(transactions.count).isZero();
    }

    // The same owner can deposit once the wallet exists (above), so only the missing wallet explains it.
    @Test
    void refusesToPostForAnOwnerWithoutWallet() {
        var owner = anOwner();

        assertThatExceptionOfType(WalletNotFoundException.class)
                .isThrownBy(() -> service.deposit(owner, money("10.50"), CORRELATION));
        assertThatExceptionOfType(WalletNotFoundException.class)
                .isThrownBy(() -> service.withdraw(owner, money("10.50"), CORRELATION));
        assertThat(ledger.isEmpty()).isTrue();
    }

    @Test
    void aRefusedWithdrawalChangesNeitherTheBalanceNorTheLedger() {
        var owner = anOwner();
        service.open(owner);

        assertThatExceptionOfType(InsufficientFundsException.class)
                .isThrownBy(() -> service.withdraw(owner, money("0.01"), CORRELATION));

        assertThat(service.get(owner).balance()).isEqualTo(Money.ZERO);
        assertThat(ledger.isEmpty()).isTrue();
    }

    @Test
    void findsATransactionOfTheOwner() {
        var owner = anOwner();
        service.open(owner);
        var deposit = service.deposit(owner, money("10.50"), CORRELATION);

        assertThat(service.transaction(owner, deposit.id())).isEqualTo(deposit);
    }

    @Test
    void hidesATransactionFromAnotherOwner() {
        var alice = anOwner();
        var bob = anOwner();
        service.open(alice);
        service.open(bob);
        var deposit = service.deposit(alice, money("10.50"), CORRELATION);

        assertThat(service.transaction(alice, deposit.id())).isEqualTo(deposit);
        assertThatExceptionOfType(TransactionNotFoundException.class)
                .isThrownBy(() -> service.transaction(bob, deposit.id()))
                .withMessage("The user has no such transaction");
    }

    @Test
    void reportsAnUnknownTransaction() {
        var owner = anOwner();
        service.open(owner);

        assertThatExceptionOfType(TransactionNotFoundException.class)
                .isThrownBy(() -> service.transaction(owner, TransactionId.newId()));
    }

    @Test
    void reportsATransactionLookupWithoutWallet() {
        assertThatExceptionOfType(WalletNotFoundException.class)
                .isThrownBy(() -> service.transaction(anOwner(), TransactionId.newId()));
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount));
    }

    private static final class CountingTransactions implements Transactions {

        private int count;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            count++;
            return work.get();
        }
    }
}
