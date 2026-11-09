package com.ebravo.wallet.application;

import static com.ebravo.wallet.Fixtures.aKey;
import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebravo.wallet.domain.BalanceLimitExceededException;
import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.IdempotencyKey;
import com.ebravo.wallet.domain.IdempotencyKeyReusedException;
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
    private final IdempotencyKeys keys = new InMemoryIdempotencyKeys();
    private final CountingTransactions transactions = new CountingTransactions();
    private final WalletService service =
            new WalletService(wallets, ledger, keys, transactions, Clock.fixed(NOW_IN_NANOS, ZoneOffset.UTC));

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

        var deposit = service.deposit(owner, money("10.50"), CORRELATION, aKey());

        assertThat(deposit).usingRecursiveComparison().ignoringFields("id").isEqualTo(new WalletTransaction(
                TransactionId.newId(), TransactionType.DEPOSIT, money("10.50"), money("10.50"), Optional.empty(), CORRELATION, NOW_IN_MICROS));
        assertThat(service.get(owner).balance()).isEqualTo(money("10.50"));
        assertThat(ledger.find(wallet.id(), deposit.id())).contains(deposit);
    }

    @Test
    void replaysADepositPostedWithTheSameKey() {
        var owner = anOwner();
        service.open(owner);
        var key = aKey();
        var first = service.deposit(owner, money("10.50"), CORRELATION, key);

        var retry = service.deposit(owner, money("10.50"), new CorrelationId("request-2"), key);

        assertThat(retry).isEqualTo(first);
        assertThat(service.get(owner).balance()).isEqualTo(money("10.50"));
        assertThat(ledger.size()).isEqualTo(1);
    }

    // The same key and amount replay (above); one cent apart, only the amount explains the refusal.
    @Test
    void refusesAKeyReusedForAnotherAmount() {
        var owner = anOwner();
        service.open(owner);
        var key = aKey();
        service.deposit(owner, money("10.50"), CORRELATION, key);

        assertThatExceptionOfType(IdempotencyKeyReusedException.class)
                .isThrownBy(() -> service.deposit(owner, money("10.51"), CORRELATION, key))
                .withMessage("The Idempotency-Key was already used for another request");
        assertThat(service.get(owner).balance()).isEqualTo(money("10.50"));
        assertThat(ledger.size()).isEqualTo(1);
    }

    @Test
    void eachNewKeyPostsANewDeposit() {
        var owner = anOwner();
        service.open(owner);
        var first = service.deposit(owner, money("10.50"), CORRELATION, aKey());

        var second = service.deposit(owner, money("10.50"), CORRELATION, aKey());

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(service.get(owner).balance()).isEqualTo(money("21.00"));
        assertThat(ledger.size()).isEqualTo(2);
    }

    @Test
    void aKeyIsScopedToItsWallet() {
        var alice = anOwner();
        var bob = anOwner();
        service.open(alice);
        service.open(bob);
        var key = new IdempotencyKey("same-key");
        var aliceDeposit = service.deposit(alice, money("10.50"), CORRELATION, key);

        var bobDeposit = service.deposit(bob, money("3.00"), CORRELATION, key);

        assertThat(bobDeposit.id()).isNotEqualTo(aliceDeposit.id());
        assertThat(service.get(alice).balance()).isEqualTo(money("10.50"));
        assertThat(service.get(bob).balance()).isEqualTo(money("3.00"));
    }

    // Refused under the lock, after the key was looked up: only a posting keeps its key.
    @Test
    void aRefusedDepositLeavesItsKeyFree() {
        var owner = anOwner();
        service.open(owner);
        service.deposit(owner, money("99999999999999999.99"), CORRELATION, aKey());
        var key = aKey();
        assertThatExceptionOfType(BalanceLimitExceededException.class)
                .isThrownBy(() -> service.deposit(owner, money("0.01"), CORRELATION, key));
        service.withdraw(owner, money("1.00"), CORRELATION, aKey());

        var retry = service.deposit(owner, money("0.01"), CORRELATION, key);

        assertThat(retry.balance()).isEqualTo(money("99999999999999999.00"));
        assertThat(ledger.size()).isEqualTo(3);
    }

    @Test
    void withdrawsFromTheWalletOfTheOwner() {
        var owner = anOwner();
        var wallet = service.open(owner);
        service.deposit(owner, money("10.50"), CORRELATION, aKey());

        var withdrawal = service.withdraw(owner, money("0.50"), new CorrelationId("request-2"), aKey());

        assertThat(withdrawal).usingRecursiveComparison().ignoringFields("id").isEqualTo(new WalletTransaction(
                TransactionId.newId(), TransactionType.WITHDRAWAL, money("-0.50"), money("10.00"),
                Optional.empty(), new CorrelationId("request-2"), NOW_IN_MICROS));
        assertThat(service.get(owner).balance()).isEqualTo(money("10.00"));
        assertThat(ledger.find(wallet.id(), withdrawal.id())).contains(withdrawal);
    }

    // The whole balance is withdrawn: had the retry been posted again, it would have been refused.
    @Test
    void replaysAWithdrawalPostedWithTheSameKey() {
        var owner = anOwner();
        service.open(owner);
        service.deposit(owner, money("10.50"), CORRELATION, aKey());
        var key = aKey();
        var first = service.withdraw(owner, money("10.50"), CORRELATION, key);

        var retry = service.withdraw(owner, money("10.50"), new CorrelationId("request-2"), key);

        assertThat(retry).isEqualTo(first);
        assertThat(service.get(owner).balance()).isEqualTo(Money.ZERO);
        assertThat(ledger.size()).isEqualTo(2);
    }

    // Enough balance for both, and the same key and amount replay (above): only the amount explains it.
    @Test
    void refusesAWithdrawalKeyReusedForAnotherAmount() {
        var owner = anOwner();
        service.open(owner);
        service.deposit(owner, money("20.00"), CORRELATION, aKey());
        var key = aKey();
        service.withdraw(owner, money("5.00"), CORRELATION, key);

        assertThatExceptionOfType(IdempotencyKeyReusedException.class)
                .isThrownBy(() -> service.withdraw(owner, money("5.01"), CORRELATION, key));
        assertThat(service.get(owner).balance()).isEqualTo(money("15.00"));
        assertThat(ledger.size()).isEqualTo(2);
    }

    // The deposit's key and amount, sent as a withdrawal: only the operation differs.
    @Test
    void refusesAKeyReusedOnAnotherOperation() {
        var owner = anOwner();
        service.open(owner);
        var key = aKey();
        service.deposit(owner, money("10.50"), CORRELATION, key);

        assertThatExceptionOfType(IdempotencyKeyReusedException.class)
                .isThrownBy(() -> service.withdraw(owner, money("10.50"), CORRELATION, key));
        assertThat(service.get(owner).balance()).isEqualTo(money("10.50"));
        assertThat(ledger.size()).isEqualTo(1);
    }

    @Test
    void eachPostingRunsInOneTransaction() {
        var owner = anOwner();
        service.open(owner);

        service.deposit(owner, money("10.50"), CORRELATION, aKey());
        service.withdraw(owner, money("0.50"), CORRELATION, aKey());

        assertThat(transactions.count).isEqualTo(2);
    }

    // Valid postings open one transaction each (above); a hopeless one must not even take the lock.
    @Test
    void refusesANonPositiveAmountBeforeTouchingTheWallet() {
        var owner = anOwner();
        service.open(owner);

        assertThatExceptionOfType(InvalidAmountException.class)
                .isThrownBy(() -> service.deposit(owner, Money.ZERO, CORRELATION, aKey()));
        assertThatExceptionOfType(InvalidAmountException.class)
                .isThrownBy(() -> service.withdraw(owner, money("-0.01"), CORRELATION, aKey()));
        assertThat(transactions.count).isZero();
    }

    // The same owner can deposit once the wallet exists (above), so only the missing wallet explains it.
    @Test
    void refusesToPostForAnOwnerWithoutWallet() {
        var owner = anOwner();

        assertThatExceptionOfType(WalletNotFoundException.class)
                .isThrownBy(() -> service.deposit(owner, money("10.50"), CORRELATION, aKey()));
        assertThatExceptionOfType(WalletNotFoundException.class)
                .isThrownBy(() -> service.withdraw(owner, money("10.50"), CORRELATION, aKey()));
        assertThat(ledger.isEmpty()).isTrue();
    }

    @Test
    void aRefusedWithdrawalChangesNeitherTheBalanceNorTheLedger() {
        var owner = anOwner();
        service.open(owner);

        assertThatExceptionOfType(InsufficientFundsException.class)
                .isThrownBy(() -> service.withdraw(owner, money("0.01"), CORRELATION, aKey()));

        assertThat(service.get(owner).balance()).isEqualTo(Money.ZERO);
        assertThat(ledger.isEmpty()).isTrue();
    }

    @Test
    void transfersFromTheWalletOfTheOwnerToTheRecipient() {
        var alice = anOwner();
        var bob = anOwner();
        var sender = service.open(alice);
        var recipient = service.open(bob);
        service.deposit(alice, money("25.00"), CORRELATION, aKey());

        var transfer = service.transfer(alice, recipient.id(), money("10.50"), new CorrelationId("request-2"), aKey());

        assertThat(transfer).usingRecursiveComparison().ignoringFields("id").isEqualTo(new WalletTransaction(
                TransactionId.newId(), TransactionType.TRANSFER, money("-10.50"), money("14.50"),
                Optional.of(recipient.id()), new CorrelationId("request-2"), NOW_IN_MICROS));
        assertThat(service.get(alice).balance()).isEqualTo(money("14.50"));
        assertThat(service.get(bob).balance()).isEqualTo(money("10.50"));
        assertThat(ledger.find(sender.id(), transfer.id())).contains(transfer);
        assertThat(ledger.find(recipient.id(), transfer.id())).map(WalletTransaction::amount).contains(money("10.50"));
    }

    // The whole balance is transferred: had the retry been posted again, it would have been refused.
    @Test
    void replaysATransferPostedWithTheSameKey() {
        var alice = anOwner();
        service.open(alice);
        var recipient = service.open(anOwner());
        service.deposit(alice, money("25.00"), CORRELATION, aKey());
        var key = aKey();
        var first = service.transfer(alice, recipient.id(), money("25.00"), CORRELATION, key);

        var retry = service.transfer(alice, recipient.id(), money("25.00"), new CorrelationId("request-2"), key);

        assertThat(retry).isEqualTo(first);
        assertThat(service.get(alice).balance()).isEqualTo(Money.ZERO);
        assertThat(service.get(recipient.owner()).balance()).isEqualTo(money("25.00"));
        assertThat(ledger.size()).isEqualTo(2);
    }

    // Same key and amount, another recipient with room for it: only the recipient explains the refusal.
    @Test
    void refusesATransferKeyReusedForAnotherRecipient() {
        var alice = anOwner();
        service.open(alice);
        var bob = service.open(anOwner());
        var carol = service.open(anOwner());
        service.deposit(alice, money("25.00"), CORRELATION, aKey());
        var key = aKey();
        service.transfer(alice, bob.id(), money("10.00"), CORRELATION, key);

        assertThatExceptionOfType(IdempotencyKeyReusedException.class)
                .isThrownBy(() -> service.transfer(alice, carol.id(), money("10.00"), CORRELATION, key));
        assertThat(service.get(alice).balance()).isEqualTo(money("15.00"));
        assertThat(service.get(carol.owner()).balance()).isEqualTo(Money.ZERO);
        assertThat(ledger.size()).isEqualTo(2);
    }

    @Test
    void refusesATransferKeyReusedForAnotherAmount() {
        var alice = anOwner();
        service.open(alice);
        var bob = service.open(anOwner());
        service.deposit(alice, money("25.00"), CORRELATION, aKey());
        var key = aKey();
        service.transfer(alice, bob.id(), money("10.00"), CORRELATION, key);

        assertThatExceptionOfType(IdempotencyKeyReusedException.class)
                .isThrownBy(() -> service.transfer(alice, bob.id(), money("10.01"), CORRELATION, key));
        assertThat(service.get(alice).balance()).isEqualTo(money("15.00"));
        assertThat(service.get(bob.owner()).balance()).isEqualTo(money("10.00"));
        assertThat(ledger.size()).isEqualTo(2);
    }

    // Sent with a fresh key, the same request is refused for the recipient (refusesATransferToAnUnknownWallet):
    // the key is looked up first, so a reused key is what the client hears.
    @Test
    void refusesAUsedKeySentToAnUnknownRecipientAsReused() {
        var alice = anOwner();
        service.open(alice);
        var bob = service.open(anOwner());
        service.deposit(alice, money("25.00"), CORRELATION, aKey());
        var key = aKey();
        service.transfer(alice, bob.id(), money("10.00"), CORRELATION, key);

        assertThatExceptionOfType(IdempotencyKeyReusedException.class)
                .isThrownBy(() -> service.transfer(alice, WalletId.newId(), money("10.00"), CORRELATION, key));
        assertThat(service.get(alice).balance()).isEqualTo(money("15.00"));
    }

    // Checked before any lock, so the key is never looked up: a transfer to oneself is refused as such.
    @Test
    void refusesAUsedKeySentToTheOwnersOwnWalletAsASameWalletTransfer() {
        var alice = anOwner();
        var wallet = service.open(alice);
        var bob = service.open(anOwner());
        service.deposit(alice, money("25.00"), CORRELATION, aKey());
        var key = aKey();
        service.transfer(alice, bob.id(), money("10.00"), CORRELATION, key);

        assertThatExceptionOfType(SameWalletTransferException.class)
                .isThrownBy(() -> service.transfer(alice, wallet.id(), money("10.00"), CORRELATION, key));
        assertThat(service.get(alice).balance()).isEqualTo(money("15.00"));
    }

    // Refused under the locks, after the key was looked up: only a posting keeps its key.
    @Test
    void aRefusedTransferLeavesItsKeyFree() {
        var alice = anOwner();
        service.open(alice);
        var bob = service.open(anOwner());
        var key = aKey();
        assertThatExceptionOfType(InsufficientFundsException.class)
                .isThrownBy(() -> service.transfer(alice, bob.id(), money("10.00"), CORRELATION, key));
        service.deposit(alice, money("10.00"), CORRELATION, aKey());

        var retry = service.transfer(alice, bob.id(), money("10.00"), CORRELATION, key);

        assertThat(retry.balance()).isEqualTo(Money.ZERO);
        assertThat(service.get(bob.owner()).balance()).isEqualTo(money("10.00"));
    }

    @Test
    void eachTransferRunsInOneTransaction() {
        var alice = anOwner();
        service.open(alice);
        var recipient = service.open(anOwner());
        service.deposit(alice, money("25.00"), CORRELATION, aKey());

        service.transfer(alice, recipient.id(), money("10.50"), CORRELATION, aKey());

        assertThat(transactions.count).isEqualTo(2);
    }

    @Test
    void locksBothWalletsInIdOrderWhicheverWayMoneyMoves() {
        var locks = new RecordingLocks(wallets);
        var recordingService = new WalletService(locks, ledger, keys, transactions, Clock.fixed(NOW_IN_NANOS, ZoneOffset.UTC));
        var alice = anOwner();
        var bob = anOwner();
        var aliceWallet = service.open(alice).id();
        var bobWallet = service.open(bob).id();
        service.deposit(alice, money("10.00"), CORRELATION, aKey());
        service.deposit(bob, money("10.00"), CORRELATION, aKey());
        var inOrder = Stream.of(aliceWallet, bobWallet).sorted().toList();

        recordingService.transfer(alice, bobWallet, money("1.00"), CORRELATION, aKey());
        assertThat(locks.taken).containsExactlyElementsOf(inOrder);
        locks.taken.clear();
        recordingService.transfer(bob, aliceWallet, money("1.00"), CORRELATION, aKey());
        assertThat(locks.taken).containsExactlyElementsOf(inOrder);
    }

    // The same owner can transfer to an existing wallet (above), so only the unknown id explains it.
    @Test
    void refusesATransferToAnUnknownWallet() {
        var alice = anOwner();
        service.open(alice);
        service.deposit(alice, money("25.00"), CORRELATION, aKey());
        var depositsOnly = ledger.size();

        assertThatExceptionOfType(RecipientNotFoundException.class)
                .isThrownBy(() -> service.transfer(alice, WalletId.newId(), money("10.50"), CORRELATION, aKey()))
                .withMessage("The recipient wallet does not exist");
        assertThat(service.get(alice).balance()).isEqualTo(money("25.00"));
        assertThat(ledger.size()).isEqualTo(depositsOnly);
    }

    // A transfer to another wallet opens a transaction (eachTransferRunsInOneTransaction); this one
    // can never succeed, so it must not wait for its wallet's lock either.
    @Test
    void refusesATransferToTheOwnersOwnWalletBeforeTouchingIt() {
        var alice = anOwner();
        var wallet = service.open(alice);
        service.deposit(alice, money("25.00"), CORRELATION, aKey());
        var before = transactions.count;

        assertThatExceptionOfType(SameWalletTransferException.class)
                .isThrownBy(() -> service.transfer(alice, wallet.id(), money("10.50"), CORRELATION, aKey()));
        assertThat(transactions.count).isEqualTo(before);
        assertThat(service.get(alice).balance()).isEqualTo(money("25.00"));
    }

    @Test
    void refusesATransferFromAnOwnerWithoutWallet() {
        var recipient = service.open(anOwner());

        assertThatExceptionOfType(WalletNotFoundException.class)
                .isThrownBy(() -> service.transfer(anOwner(), recipient.id(), money("10.50"), CORRELATION, aKey()));
        assertThat(service.get(recipient.owner()).balance()).isEqualTo(Money.ZERO);
        assertThat(ledger.isEmpty()).isTrue();
    }

    @Test
    void aRefusedTransferChangesNeitherBalanceNorTheLedger() {
        var alice = anOwner();
        service.open(alice);
        var recipient = service.open(anOwner());

        assertThatExceptionOfType(InsufficientFundsException.class)
                .isThrownBy(() -> service.transfer(alice, recipient.id(), money("0.01"), CORRELATION, aKey()));

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
                .isThrownBy(() -> service.transfer(alice, recipient.id(), Money.ZERO, CORRELATION, aKey()));
        assertThat(transactions.count).isZero();
    }

    @Test
    void findsATransactionOfTheOwner() {
        var owner = anOwner();
        service.open(owner);
        var deposit = service.deposit(owner, money("10.50"), CORRELATION, aKey());

        assertThat(service.transaction(owner, deposit.id())).isEqualTo(deposit);
    }

    @Test
    void hidesATransactionFromAnotherOwner() {
        var alice = anOwner();
        var bob = anOwner();
        service.open(alice);
        service.open(bob);
        var deposit = service.deposit(alice, money("10.50"), CORRELATION, aKey());

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

    @Test
    void listsTheOwnersTransactionsNewestFirst() {
        var owner = anOwner();
        service.open(owner);
        var deposit = service.deposit(owner, money("10.50"), CORRELATION, aKey());
        var withdrawal = service.withdraw(owner, money("0.50"), CORRELATION, aKey());

        var page = service.transactions(owner, new PageRequest(0, 20));

        assertThat(page.transactions()).containsExactly(withdrawal, deposit);
        assertThat(page.total()).isEqualTo(2);
    }

    // Bob's own deposit is listed, so not seeing Alice's is not an empty answer.
    @Test
    void listsNoTransactionOfAnotherOwner() {
        var alice = anOwner();
        var bob = anOwner();
        service.open(alice);
        service.open(bob);
        service.deposit(alice, money("10.50"), CORRELATION, aKey());
        var his = service.deposit(bob, money("1.00"), CORRELATION, aKey());

        assertThat(service.transactions(bob, new PageRequest(0, 20)).transactions()).containsExactly(his);
    }

    @Test
    void reportsAListingWithoutWallet() {
        assertThatExceptionOfType(WalletNotFoundException.class)
                .isThrownBy(() -> service.transactions(anOwner(), new PageRequest(0, 20)));
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
