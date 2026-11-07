package com.ebravo.wallet.application;

import static com.ebravo.wallet.Fixtures.NOW;
import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;

import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.Wallet;
import com.ebravo.wallet.domain.WalletId;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Behavior every {@link WalletRepository} must have, run against each implementation. */
public interface WalletRepositoryContract {

    WalletRepository repository();

    @Test
    default void storesTheWalletOfAnOwnerWithoutOne() {
        var wallet = Wallet.open(anOwner(), NOW);

        assertThat(repository().add(wallet)).isTrue();
        assertThat(repository().findByOwner(wallet.owner())).contains(wallet);
    }

    @Test
    default void keepsEveryFieldAsStored() {
        var wallet = new Wallet(WalletId.newId(), anOwner(), new Money(new BigDecimal("1234.50")), NOW);

        repository().add(wallet);

        assertThat(repository().findByOwner(wallet.owner())).contains(wallet);
    }

    @Test
    default void keepsTheFirstWalletWhenTheOwnerAddsAnother() {
        var owner = anOwner();
        var first = Wallet.open(owner, NOW);
        repository().add(first);

        assertThat(repository().add(Wallet.open(owner, NOW))).isFalse();
        assertThat(repository().findByOwner(owner)).contains(first);
    }

    @Test
    default void findsNothingForAnOwnerWithoutWallet() {
        assertThat(repository().findByOwner(anOwner())).isEmpty();
    }
}
