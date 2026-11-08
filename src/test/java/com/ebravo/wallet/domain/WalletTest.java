package com.ebravo.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WalletTest {

    private static final OwnerId OWNER = new OwnerId("owner");
    private static final Instant NOW = Instant.parse("2026-11-07T14:53:35.123456Z");
    private static final Instant LATER = Instant.parse("2026-11-08T09:00:00.654321Z");
    private static final CorrelationId CORRELATION = new CorrelationId("request-1");

    @Test
    void opensWithZeroBalance() {
        var wallet = Wallet.open(OWNER, NOW);

        assertThat(wallet.id()).isNotNull();
        assertThat(wallet.owner()).isEqualTo(OWNER);
        assertThat(wallet.balance()).isEqualTo(Money.ZERO);
        assertThat(wallet.createdAt()).isEqualTo(NOW);
    }

    @Test
    void eachOpenedWalletHasItsOwnId() {
        assertThat(Wallet.open(OWNER, NOW).id()).isNotEqualTo(Wallet.open(OWNER, NOW).id());
    }

    @Test
    void rejectsMissingFields() {
        var id = WalletId.newId();

        assertThatNullPointerException().isThrownBy(() -> new Wallet(null, OWNER, Money.ZERO, NOW)).withMessage("id");
        assertThatNullPointerException().isThrownBy(() -> new Wallet(id, null, Money.ZERO, NOW)).withMessage("owner");
        assertThatNullPointerException().isThrownBy(() -> new Wallet(id, OWNER, null, NOW)).withMessage("balance");
        assertThatNullPointerException().isThrownBy(() -> new Wallet(id, OWNER, Money.ZERO, null)).withMessage("createdAt");
    }

    @Test
    void depositRaisesTheBalanceAndPostsABalancedTransaction() {
        var wallet = withBalance("25.00");

        var posting = wallet.deposit(money("10.50"), CORRELATION, LATER);

        assertThat(posting.wallets()).containsExactly(new Wallet(wallet.id(), OWNER, money("35.50"), NOW));
        var transaction = posting.transaction();
        assertThat(transaction.type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(transaction.entries()).containsExactly(
                new ExternalEntry(money("-10.50")), new WalletEntry(wallet.id(), money("10.50"), money("35.50")));
        assertThat(transaction.correlationId()).isEqualTo(CORRELATION);
        assertThat(transaction.createdAt()).isEqualTo(LATER);
    }

    @Test
    void withdrawalLowersTheBalanceAndPostsABalancedTransaction() {
        var wallet = withBalance("25.00");

        var posting = wallet.withdraw(money("10.50"), CORRELATION, LATER);

        assertThat(posting.wallets()).containsExactly(new Wallet(wallet.id(), OWNER, money("14.50"), NOW));
        var transaction = posting.transaction();
        assertThat(transaction.type()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(transaction.entries()).containsExactly(
                new WalletEntry(wallet.id(), money("-10.50"), money("14.50")), new ExternalEntry(money("10.50")));
        assertThat(transaction.correlationId()).isEqualTo(CORRELATION);
        assertThat(transaction.createdAt()).isEqualTo(LATER);
    }

    @Test
    void eachPostingHasItsOwnTransactionId() {
        var wallet = withBalance("25.00");

        assertThat(wallet.deposit(money("1.00"), CORRELATION, LATER).transaction().id())
                .isNotEqualTo(wallet.deposit(money("1.00"), CORRELATION, LATER).transaction().id());
    }

    @Test
    void withdrawingTheWholeBalanceLeavesZero() {
        assertThat(withBalance("25.00").withdraw(money("25.00"), CORRELATION, LATER).wallets().getFirst().balance()).isEqualTo(Money.ZERO);
    }

    // The whole balance can be withdrawn (above), so only the extra cent explains the rejection.
    @Test
    void rejectsAWithdrawalAboveTheBalance() {
        var wallet = withBalance("25.00");

        assertThatExceptionOfType(InsufficientFundsException.class)
                .isThrownBy(() -> wallet.withdraw(money("25.01"), CORRELATION, LATER))
                .withMessage("The balance is not enough for this withdrawal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-0.01"})
    void rejectsADepositThatIsNotPositive(String amount) {
        assertThatExceptionOfType(InvalidAmountException.class)
                .isThrownBy(() -> withBalance("25.00").deposit(money(amount), CORRELATION, LATER))
                .withMessage("Amount must be positive: " + amount);
    }

    // A negative amount would otherwise pass the balance check and raise the balance.
    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-0.01"})
    void rejectsAWithdrawalThatIsNotPositive(String amount) {
        assertThatExceptionOfType(InvalidAmountException.class)
                .isThrownBy(() -> withBalance("25.00").withdraw(money(amount), CORRELATION, LATER))
                .withMessage("Amount must be positive: " + amount);
    }

    @Test
    void acceptsADepositThatReachesTheLargestStorableBalance() {
        var wallet = withBalance("99999999999999989.99");

        assertThat(wallet.deposit(money("10.00"), CORRELATION, LATER).wallets().getFirst().balance()).isEqualTo(Money.MAX);
    }

    @Test
    void rejectsADepositThatWouldExceedTheLargestStorableBalance() {
        var wallet = withBalance("99999999999999989.99");

        assertThatExceptionOfType(BalanceLimitExceededException.class)
                .isThrownBy(() -> wallet.deposit(money("10.01"), CORRELATION, LATER))
                .withMessage("The balance would exceed 99999999999999999.99");
    }

    private static Wallet withBalance(String balance) {
        return new Wallet(WalletId.newId(), OWNER, money(balance), NOW);
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount));
    }
}
