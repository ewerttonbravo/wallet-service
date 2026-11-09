package com.ebravo.wallet.application;

import static com.ebravo.wallet.Fixtures.NOW;
import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;

import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.IdempotencyKey;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.Wallet;
import com.ebravo.wallet.domain.WalletId;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Behavior every {@link IdempotencyKeys} must have, run against each implementation. */
public interface IdempotencyKeysContract {

    IdempotencyKey KEY = new IdempotencyKey("deposit-1");

    IdempotencyKeys keys();

    Ledger ledger();

    WalletRepository wallets();

    Transactions transactions();

    @Test
    default void findsTheTransactionRecordedForAKey() {
        var wallet = aWallet();
        var deposit = aDepositInto(wallet);

        record(wallet.id(), KEY, deposit);

        assertThat(find(wallet.id(), KEY)).contains(deposit);
    }

    @Test
    default void theSameKeyNamesItsOwnTransactionOnEachWallet() {
        var alice = aWallet();
        var bob = aWallet();
        var aliceDeposit = aDepositInto(alice);
        var bobDeposit = aDepositInto(bob);

        record(alice.id(), KEY, aliceDeposit);
        record(bob.id(), KEY, bobDeposit);

        assertThat(find(alice.id(), KEY)).contains(aliceDeposit);
        assertThat(find(bob.id(), KEY)).contains(bobDeposit);
    }

    // The key is found on the wallet that used it, so the other wallet's miss can only be its scope.
    @Test
    default void findsNothingForAKeyTheWalletNeverUsed() {
        var wallet = aWallet();
        var stranger = aWallet();
        record(wallet.id(), KEY, aDepositInto(wallet));

        assertThat(find(wallet.id(), KEY)).isPresent();
        assertThat(find(stranger.id(), KEY)).isEmpty();
        assertThat(find(wallet.id(), new IdempotencyKey("deposit-2"))).isEmpty();
    }

    private Wallet aWallet() {
        var wallet = Wallet.open(anOwner(), NOW);
        wallets().add(wallet);
        return wallet;
    }

    private TransactionId aDepositInto(Wallet wallet) {
        var deposit = wallet.deposit(new Money(new BigDecimal("10.00")), new CorrelationId("request-1"), NOW).transaction();
        inTransaction(() -> {
            ledger().append(deposit);
            return null;
        });
        return deposit.id();
    }

    private void record(WalletId wallet, IdempotencyKey key, TransactionId transaction) {
        inTransaction(() -> {
            keys().record(wallet, key, transaction);
            return null;
        });
    }

    private Optional<TransactionId> find(WalletId wallet, IdempotencyKey key) {
        return inTransaction(() -> keys().find(wallet, key));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transactions().inTransaction(work);
    }
}
