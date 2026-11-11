package com.ebravo.wallet.application;

import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.IdempotencyKey;
import com.ebravo.wallet.domain.IdempotencyKeyReusedException;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.OwnerId;
import com.ebravo.wallet.domain.Posting;
import com.ebravo.wallet.domain.RecipientNotFoundException;
import com.ebravo.wallet.domain.SameWalletTransferException;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.TransactionNotFoundException;
import com.ebravo.wallet.domain.Wallet;
import com.ebravo.wallet.domain.WalletAlreadyExistsException;
import com.ebravo.wallet.domain.WalletId;
import com.ebravo.wallet.domain.WalletNotFoundException;
import com.ebravo.wallet.domain.WalletTransaction;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class WalletService {

    private final WalletRepository wallets;
    private final Ledger ledger;
    private final IdempotencyKeys keys;
    private final Transactions transactions;
    private final WalletMetrics metrics;
    private final Clock clock;

    public WalletService(
            WalletRepository wallets,
            Ledger ledger,
            IdempotencyKeys keys,
            Transactions transactions,
            WalletMetrics metrics,
            Clock clock) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.keys = keys;
        this.transactions = transactions;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Wallet open(OwnerId owner) {
        var wallet = Wallet.open(owner, now());
        if (!transactions.inTransaction(() -> wallets.add(wallet))) {
            throw new WalletAlreadyExistsException();
        }
        return wallet;
    }

    public Wallet get(OwnerId owner) {
        return transactions.inReadTransaction(() -> find(owner));
    }

    /**
     * The balance after the wallet's last posting at or before the instant. Entries are stamped at the
     * microsecond, so the instant is read at the microsecond it falls in.
     */
    public Money balanceAt(OwnerId owner, Instant at) {
        var instant = at.truncatedTo(ChronoUnit.MICROS);
        return transactions.inReadTransaction(() -> {
            var wallet = find(owner);
            wallet.requireBalanceKnownAt(instant, now());
            return ledger.balanceAt(wallet.id(), instant).orElse(Money.ZERO);
        });
    }

    /** A retry with the key of a deposit already posted gets that deposit back, and posts nothing. */
    public WalletTransaction deposit(OwnerId owner, Money amount, CorrelationId correlationId, IdempotencyKey key) {
        // Checked before the lock too, so a request that can never succeed does not wait for it.
        amount.requirePositive();
        return post(owner, key, original -> original.isDepositOf(amount),
                (wallet, at) -> wallet.deposit(amount, correlationId, at));
    }

    public WalletTransaction withdraw(OwnerId owner, Money amount, CorrelationId correlationId, IdempotencyKey key) {
        amount.requirePositive();
        return post(owner, key, original -> original.isWithdrawalOf(amount),
                (wallet, at) -> wallet.withdraw(amount, correlationId, at));
    }

    /**
     * Moves money from the owner's wallet to the recipient's; the result is the transfer as the owner
     * sees it. The key is looked up once both wallets are locked, so a retry gets its transfer back
     * even when the balance would no longer cover it.
     */
    public WalletTransaction transfer(
            OwnerId owner, WalletId recipient, Money amount, CorrelationId correlationId, IdempotencyKey key) {
        amount.requirePositive();
        // A wallet's id never changes, so it is read before the transaction, where no lock is held yet.
        var source = get(owner).id();
        if (source.equals(recipient)) {
            throw new SameWalletTransferException();
        }
        return counted(transactions.inTransaction(() -> {
            // Every transfer locks its wallets in id order: two opposing transfers queue for the first
            // lock instead of each holding the one the other waits for.
            var locked = new HashMap<WalletId, Wallet>();
            for (var id : Stream.of(source, recipient).sorted().toList()) {
                wallets.lockById(id).ifPresent(wallet -> locked.put(id, wallet));
            }
            var sender = locked.get(source);
            return once(sender, key, original -> original.isTransferOf(recipient, amount), () -> {
                var target = Optional.ofNullable(locked.get(recipient)).orElseThrow(RecipientNotFoundException::new);
                return record(sender, sender.transfer(amount, target, correlationId, now()));
            });
        }));
    }

    /** Only the owner's own transactions are found: anyone else's is reported as missing. */
    public WalletTransaction transaction(OwnerId owner, TransactionId id) {
        return transactions.inReadTransaction(
                () -> ledger.find(find(owner).id(), id).orElseThrow(TransactionNotFoundException::new));
    }

    public TransactionPage transactions(OwnerId owner, PageRequest page) {
        return transactions.inReadTransaction(() -> ledger.history(find(owner).id(), page));
    }

    private Wallet find(OwnerId owner) {
        return wallets.findByOwner(owner).orElseThrow(WalletNotFoundException::new);
    }

    private WalletTransaction post(
            OwnerId owner,
            IdempotencyKey key,
            Predicate<WalletTransaction> sameRequest,
            BiFunction<Wallet, Instant, Posting> operation) {
        return counted(transactions.inTransaction(() -> {
            var wallet = wallets.lockByOwner(owner).orElseThrow(WalletNotFoundException::new);
            // Read under the lock, so a wallet's entries are timestamped in the order they were posted.
            return once(wallet, key, sameRequest, () -> record(wallet, operation.apply(wallet, now())));
        }));
    }

    /** Counted once the transaction returned, so a posting rolled back is never counted. */
    private WalletTransaction counted(Outcome outcome) {
        var type = outcome.transaction().type();
        if (outcome.replayed()) {
            metrics.replayed(type);
        } else {
            metrics.posted(type);
        }
        return outcome.transaction();
    }

    /**
     * Must run under the lock of the wallet that sent the key: a retry sent while the first request
     * is still posting waits for it there, then finds its key. A refused posting records no key, so
     * its retry runs again. A key that names a posting the request does not match is refused.
     */
    private Outcome once(
            Wallet wallet, IdempotencyKey key, Predicate<WalletTransaction> sameRequest, Supplier<WalletTransaction> posting) {
        return keys.find(wallet.id(), key)
                .map(posted -> new Outcome(replay(ledger.find(wallet.id(), posted).orElseThrow(), sameRequest), true))
                .orElseGet(() -> {
                    var posted = posting.get();
                    keys.record(wallet.id(), key, posted.id());
                    return new Outcome(posted, false);
                });
    }

    private static WalletTransaction replay(WalletTransaction original, Predicate<WalletTransaction> sameRequest) {
        if (!sameRequest.test(original)) {
            throw new IdempotencyKeyReusedException();
        }
        return original;
    }

    /** Stores every changed balance and the transaction that explains them, in the caller's transaction. */
    private WalletTransaction record(Wallet acting, Posting posting) {
        posting.wallets().forEach(wallets::updateBalance);
        ledger.append(posting.transaction());
        return posting.transaction().viewFor(acting.id()).orElseThrow();
    }

    /** A posting's result, and whether it was a replay of one already posted. */
    private record Outcome(WalletTransaction transaction, boolean replayed) {
    }

    // The database keeps microseconds; truncating makes what is returned equal to what is stored.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
