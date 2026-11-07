package com.ebravo.wallet.application;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.WalletAlreadyExistsException;
import com.ebravo.wallet.domain.WalletNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WalletServiceTest {

    private static final Instant NOW_IN_NANOS = Instant.parse("2026-11-07T14:53:35.123456789Z");

    private final WalletRepository wallets = new InMemoryWalletRepository();
    private final WalletService service = new WalletService(wallets, Clock.fixed(NOW_IN_NANOS, ZoneOffset.UTC));

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
        assertThat(service.open(anOwner()).createdAt()).isEqualTo(Instant.parse("2026-11-07T14:53:35.123456Z"));
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
}
