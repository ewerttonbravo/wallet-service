package com.ebravo.wallet.adapter.persistence;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.application.Ledger;
import com.ebravo.wallet.application.Transactions;
import com.ebravo.wallet.application.WalletRepository;
import com.ebravo.wallet.application.WalletService;
import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.LedgerTransaction;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.OwnerId;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.WalletId;
import com.ebravo.wallet.domain.WalletTransaction;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A posting writes the balance first and the ledger last. When the ledger write fails after both
 * statements ran, the transaction must take the balance back with it: no balance without its entry.
 */
@IntegrationTest
class PostingAtomicityTest {

    private static final CorrelationId CORRELATION = new CorrelationId("atomicity-test");

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private Ledger ledger;

    @Autowired
    private Transactions transactions;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcClient jdbc;

    // The same wiring with a ledger that does not fail posts both writes, so the setup itself writes.
    @Test
    void aPostingCommitsTheBalanceAndTheLedgerTogether() {
        var service = new WalletService(wallets, ledger, transactions, clock);
        var owner = anOwner();
        service.open(owner);

        service.deposit(owner, money("10.00"), CORRELATION);

        assertThat(service.get(owner).balance()).isEqualTo(money("10.00"));
        assertThat(entriesOf(service, owner)).isEqualTo(1);
    }

    @Test
    void aLedgerFailureAfterTheWritesRollsTheBalanceBack() {
        var service = new WalletService(wallets, new FailingAfterAppend(ledger), transactions, clock);
        var owner = anOwner();
        service.open(owner);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.deposit(owner, money("10.00"), CORRELATION))
                .withMessage("ledger failed after writing");

        assertThat(service.get(owner).balance()).isEqualTo(Money.ZERO);
        assertThat(entriesOf(service, owner)).isZero();
    }

    @Test
    void aLedgerFailureAfterATransferRollsBothBalancesBack() {
        var service = new WalletService(wallets, new FailingAfterAppend(ledger), transactions, clock);
        var alice = anOwner();
        var bob = anOwner();
        service.open(alice);
        var recipient = service.open(bob);
        new WalletService(wallets, ledger, transactions, clock).deposit(alice, money("10.00"), CORRELATION);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.transfer(alice, recipient.id(), money("4.00"), CORRELATION))
                .withMessage("ledger failed after writing");

        assertThat(service.get(alice).balance()).isEqualTo(money("10.00"));
        assertThat(service.get(bob).balance()).isEqualTo(Money.ZERO);
        assertThat(entriesOf(service, alice)).isEqualTo(1);
        assertThat(entriesOf(service, bob)).isZero();
    }

    private int entriesOf(WalletService service, OwnerId owner) {
        return jdbc.sql("select count(*) from ledger_entry where wallet_id = :wallet")
                .param("wallet", service.get(owner).id().value())
                .query(Integer.class)
                .single();
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount));
    }

    /** Writes the entries for real, then fails: the last step of the posting breaks after every write ran. */
    private record FailingAfterAppend(Ledger delegate) implements Ledger {

        @Override
        public void append(LedgerTransaction transaction) {
            delegate.append(transaction);
            throw new IllegalStateException("ledger failed after writing");
        }

        @Override
        public Optional<WalletTransaction> find(WalletId wallet, TransactionId id) {
            return delegate.find(wallet, id);
        }
    }
}
