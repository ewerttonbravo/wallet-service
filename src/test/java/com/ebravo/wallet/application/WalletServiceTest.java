package com.ebravo.wallet.application;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.InsufficientFundsException;
import com.ebravo.wallet.domain.InvalidAmountException;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.OwnerId;
import com.ebravo.wallet.domain.RecipientNotFoundException;
import com.ebravo.wallet.domain.SameWalletTransferException;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.TransactionNotFoundException;
import com.ebravo.wallet.domain.TransactionType;
import com.ebravo.wallet.domain.Wallet;
import com.ebravo.wallet.domain.WalletAlreadyExistsException;
import com.ebravo.wallet.domain.WalletId;
import com.ebravo.wallet.domain.WalletNotFoundException;
import com.ebravo.wallet.domain.WalletTransaction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
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
                TransactionId.newId(), TransactionType.DEPOSIT, money("10.50"), money("10.50"), Optional.empty(), CORRELATION, NOW_IN_MICROS));
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
                Optional.empty(), new CorrelationId("request-2"), NOW_IN_MICROS));
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
    void transfersFromTheWalletOfTheOwnerToTheRecipient() {
        var alice = anOwner();
        var bob = anOwner();
        var sender = service.open(alice);
        var recipient = service.open(bob);
        service.deposit(alice, money("25.00"), CORRELATION);

        var transfer = service.transfer(alice, recipient.id(), money("10.50"), new CorrelationId("request-2"));

        assertThat(transfer).usingRecursiveComparison().ignoringFields("id").isEqualTo(new WalletTransaction(
                TransactionId.newId(), TransactionType.TRANSFER, money("-10.50"), money("14.50"),
                Optional.of(recipient.id()), new CorrelationId("request-2"), NOW_IN_MICROS));
        assertThat(service.get(alice).balance()).isEqualTo(money("14.50"));
        assertThat(service.get(bob).balance()).isEqualTo(money("10.50"));
        assertThat(ledger.find(sender.id(), transfer.id())).contains(transfer);
        assertThat(ledger.find(recipient.id(), transfer.id())).map(WalletTransaction::amount).contains(money("10.50"));
    }

    @Test
    void eachTransferRunsInOneTransaction() {
        var alice = anOwner();
        service.open(alice);
        var recipient = service.open(anOwner());
        service.deposit(alice, money("25.00"), CORRELATION);

        service.transfer(alice, recipient.id(), money("10.50"), CORRELATION);

        assertThat(transactions.count).isEqualTo(2);
    }

    @Test
    void locksBothWalletsInIdOrderWhicheverWayMoneyMoves() {
        var locks = new RecordingLocks(wallets);
        var recordingService = new WalletService(locks, ledger, transactions, Clock.fixed(NOW_IN_NANOS, ZoneOffset.UTC));
        var alice = anOwner();
        var bob = anOwner();
        var aliceWallet = service.open(alice).id();
        var bobWallet = service.open(bob).id();
        service.deposit(alice, money("10.00"), CORRELATION);
        service.deposit(bob, money("10.00"), CORRELATION);
        var inOrder = Stream.of(aliceWallet, bobWallet).sorted().toList();

        recordingService.transfer(alice, bobWallet, money("1.00"), CORRELATION);
        assertThat(locks.taken).containsExactlyElementsOf(inOrder);
        locks.taken.clear();
        recordingService.transfer(bob, aliceWallet, money("1.00"), CORRELATION);
        assertThat(locks.taken).containsExactlyElementsOf(inOrder);
    }

    // The same owner can transfer to an existing wallet (above), so only the unknown id explains it.
    @Test
    void refusesATransferToAnUnknownWallet() {
        var alice = anOwner();
        service.open(alice);
        service.deposit(alice, money("25.00"), CORRELATION);
        var depositsOnly = ledgerSize();

        assertThatExceptionOfType(RecipientNotFoundException.class)
                .isThrownBy(() -> service.transfer(alice, WalletId.newId(), money("10.50"), CORRELATION))
                .withMessage("The recipient wallet does not exist");
        assertThat(service.get(alice).balance()).isEqualTo(money("25.00"));
        assertThat(ledgerSize()).isEqualTo(depositsOnly);
    }

    @Test
    void refusesATransferToTheOwnersOwnWallet() {
        var alice = anOwner();
        var wallet = service.open(alice);
        service.deposit(alice, money("25.00"), CORRELATION);

        assertThatExceptionOfType(SameWalletTransferException.class)
                .isThrownBy(() -> service.transfer(alice, wallet.id(), money("10.50"), CORRELATION));
        assertThat(service.get(alice).balance()).isEqualTo(money("25.00"));
    }

    @Test
    void refusesATransferFromAnOwnerWithoutWallet() {
        var recipient = service.open(anOwner());

        assertThatExceptionOfType(WalletNotFoundException.class)
                .isThrownBy(() -> service.transfer(anOwner(), recipient.id(), money("10.50"), CORRELATION));
        assertThat(service.get(recipient.owner()).balance()).isEqualTo(Money.ZERO);
        assertThat(ledger.isEmpty()).isTrue();
    }

    @Test
    void aRefusedTransferChangesNeitherBalanceNorTheLedger() {
        var alice = anOwner();
        service.open(alice);
        var recipient = service.open(anOwner());

        assertThatExceptionOfType(InsufficientFundsException.class)
                .isThrownBy(() -> service.transfer(alice, recipient.id(), money("0.01"), CORRELATION));

        assertThat(service.get(alice).balance()).isEqualTo(Money.ZERO);
        assertThat(service.get(recipient.owner()).balance()).isEqualTo(Money.ZERO);
        assertThat(ledger.isEmpty()).isTrue();
    }

    // A valid transfer opens one transaction (above); a hopeless one must not even take the locks.
    @Test
    void refusesANonPositiveTransferBeforeTouchingTheWallets() {
        var alice = anOwner();
        service.open(alice);
        var recipient = service.open(anOwner());

        assertThatExceptionOfType(InvalidAmountException.class)
                .isThrownBy(() -> service.transfer(alice, recipient.id(), Money.ZERO, CORRELATION));
        assertThat(transactions.count).isZero();
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

    private long ledgerSize() {
        return ledger.size();
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount));
    }

    /** Records which wallets are locked, in order, by either lock method. */
    private static final class RecordingLocks implements WalletRepository {

        private final WalletRepository delegate;
        private final List<WalletId> taken = new ArrayList<>();

        private RecordingLocks(WalletRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean add(Wallet wallet) {
            return delegate.add(wallet);
        }

        @Override
        public Optional<Wallet> findByOwner(OwnerId owner) {
            return delegate.findByOwner(owner);
        }

        @Override
        public Optional<Wallet> lockByOwner(OwnerId owner) {
            var wallet = delegate.lockByOwner(owner);
            wallet.ifPresent(it -> taken.add(it.id()));
            return wallet;
        }

        @Override
        public Optional<Wallet> lockById(WalletId id) {
            taken.add(id);
            return delegate.lockById(id);
        }

        @Override
        public void updateBalance(Wallet wallet) {
            delegate.updateBalance(wallet);
        }
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
