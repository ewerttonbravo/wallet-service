package com.ebravo.wallet.adapter.persistence;

import static com.ebravo.wallet.Fixtures.aKey;
import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.application.WalletService;
import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.InsufficientFundsException;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.OwnerId;
import com.ebravo.wallet.domain.WalletTransaction;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Postings on one wallet, released together on virtual threads, against the real database. The
 * reconciliation reads only the ledger, so it cannot agree with a wrong stored balance by accident.
 */
@IntegrationTest
class LedgerConcurrencyTest {

    private static final CorrelationId CORRELATION = new CorrelationId("concurrency-test");

    @Autowired
    private WalletService wallets;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void concurrentWithdrawalsNeverOverdrawTheWallet() {
        var owner = aWalletWith("100.00");

        var attempts = releasedTogether(IntStream.range(0, 32)
                .<Callable<WalletTransaction>>mapToObj(_ -> () -> wallets.withdraw(owner, money("10.00"), CORRELATION))
                .toList());

        assertThat(succeeded(attempts)).isEqualTo(10);
        assertThat(attempts).filteredOn(it -> it.state() == Future.State.FAILED)
                .hasSize(22)
                .allMatch(it -> it.exceptionNow() instanceof InsufficientFundsException);
        assertThat(wallets.get(owner).balance()).isEqualTo(Money.ZERO);
        assertReconciles(owner);
    }

    @Test
    void theStoredBalanceReconcilesWithTheLedgerAfterConcurrentPostings() {
        var owner = aWalletWith("20.00");

        var attempts = releasedTogether(IntStream.range(0, 32)
                .<Callable<WalletTransaction>>mapToObj(i -> i % 2 == 0
                        ? () -> wallets.deposit(owner, money("1.00"), CORRELATION, aKey())
                        : () -> wallets.withdraw(owner, money("3.00"), CORRELATION))
                .toList());

        var withdrawals = succeeded(attempts) - 16;
        var withdrawn = new Money(new BigDecimal("3.00").multiply(BigDecimal.valueOf(withdrawals)));
        assertThat(wallets.get(owner).balance()).isEqualTo(money("36.00").subtract(withdrawn));
        assertReconciles(owner);
    }

    /** A client retrying a deposit it got no answer for, many times at once: one deposit is posted. */
    @Test
    void concurrentRetriesOfADepositPostItOnce() {
        var owner = aWalletWith("20.00");
        var key = aKey();

        var attempts = releasedTogether(IntStream.range(0, 16)
                .<Callable<WalletTransaction>>mapToObj(_ -> () -> wallets.deposit(owner, money("5.00"), CORRELATION, key))
                .toList());

        assertThat(attempts).allSatisfy(it -> assertThat(it.state()).isEqualTo(Future.State.SUCCESS));
        assertThat(attempts.stream().map(Future::resultNow).distinct()).hasSize(1);
        assertThat(wallets.get(owner).balance()).isEqualTo(money("25.00"));
        assertReconciles(owner);
    }

    /**
     * Each transfer holds one wallet and waits for the other. Locked sender first, a transfer from A
     * to B and one from B to A would each hold what the other needs; locked in id order, they queue.
     */
    @Test
    void opposingTransfersNeverDeadlock() {
        var alice = aWalletWith("100.00");
        var bob = aWalletWith("100.00");
        var aliceWallet = wallets.get(alice).id();
        var bobWallet = wallets.get(bob).id();

        var attempts = releasedTogether(IntStream.range(0, 32)
                .<Callable<WalletTransaction>>mapToObj(i -> i % 2 == 0
                        ? () -> wallets.transfer(alice, bobWallet, money("1.00"), CORRELATION)
                        : () -> wallets.transfer(bob, aliceWallet, money("1.00"), CORRELATION))
                .toList());

        assertThat(attempts).allSatisfy(it -> assertThat(it.state()).isEqualTo(Future.State.SUCCESS));
        assertThat(wallets.get(alice).balance()).isEqualTo(money("100.00"));
        assertThat(wallets.get(bob).balance()).isEqualTo(money("100.00"));
        assertReconciles(alice);
        assertReconciles(bob);
    }

    @Test
    void concurrentTransfersNeverOverdrawTheSender() {
        var alice = aWalletWith("100.00");
        var bob = aWalletWith("5.00");
        var bobWallet = wallets.get(bob).id();

        var attempts = releasedTogether(IntStream.range(0, 32)
                .<Callable<WalletTransaction>>mapToObj(_ -> () -> wallets.transfer(alice, bobWallet, money("10.00"), CORRELATION))
                .toList());

        assertThat(succeeded(attempts)).isEqualTo(10);
        assertThat(attempts).filteredOn(it -> it.state() == Future.State.FAILED)
                .hasSize(22)
                .allMatch(it -> it.exceptionNow() instanceof InsufficientFundsException);
        assertThat(wallets.get(alice).balance()).isEqualTo(Money.ZERO);
        assertThat(wallets.get(bob).balance()).isEqualTo(money("105.00"));
        assertReconciles(alice);
        assertReconciles(bob);
    }

    private OwnerId aWalletWith(String balance) {
        var owner = anOwner();
        wallets.open(owner);
        wallets.deposit(owner, money(balance), CORRELATION, aKey());
        return owner;
    }

    private static List<Future<WalletTransaction>> releasedTogether(List<Callable<WalletTransaction>> postings) {
        var start = new CountDownLatch(1);
        List<Future<WalletTransaction>> attempts;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            attempts = postings.stream().map(posting -> executor.submit(() -> {
                start.await();
                return posting.call();
            })).toList();
            start.countDown();
        }
        return attempts;
    }

    private static int succeeded(List<Future<WalletTransaction>> attempts) {
        return (int) attempts.stream().filter(it -> it.state() == Future.State.SUCCESS).count();
    }

    /**
     * The stored balance equals the sum of the wallet's entries; each entry's resulting balance
     * follows from the one before it; every transaction touching the wallet sums to zero.
     */
    private void assertReconciles(OwnerId owner) {
        var wallet = wallets.get(owner);
        var entries = jdbc.sql("select amount, balance_after from ledger_entry where wallet_id = :wallet order by id")
                .param("wallet", wallet.id().value())
                .query((rs, _) -> new Entry(new Money(rs.getBigDecimal("amount")), new Money(rs.getBigDecimal("balance_after"))))
                .list();

        var running = Money.ZERO;
        for (var entry : entries) {
            running = running.add(entry.amount());
            assertThat(entry.balanceAfter()).isEqualTo(running);
        }
        assertThat(wallet.balance()).isEqualTo(running);
        assertThat(jdbc.sql("""
                        select transaction_id from ledger_entry
                        where transaction_id in (select transaction_id from ledger_entry where wallet_id = :wallet)
                        group by transaction_id having sum(amount) <> 0
                        """)
                .param("wallet", wallet.id().value())
                .query(UUID.class)
                .list()).isEmpty();
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount));
    }

    private record Entry(Money amount, Money balanceAfter) {
    }
}
