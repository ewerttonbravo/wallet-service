package com.ebravo.wallet.adapter.persistence;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.application.WalletService;
import com.ebravo.wallet.domain.Wallet;
import com.ebravo.wallet.domain.WalletAlreadyExistsException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class WalletCreationConcurrencyTest {

    private static final int ATTEMPTS = 16;

    @Autowired
    private WalletService wallets;

    @Test
    void concurrentOpeningsForTheSameOwnerCreateExactlyOneWallet() {
        var owner = anOwner();
        var start = new CountDownLatch(1);
        List<Future<Wallet>> attempts;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            attempts = IntStream.range(0, ATTEMPTS)
                    .mapToObj(_ -> executor.submit(() -> {
                        start.await();
                        return wallets.open(owner);
                    }))
                    .toList();
            start.countDown();
        }

        var opened = attempts.stream().filter(it -> it.state() == Future.State.SUCCESS).map(Future::resultNow).toList();
        var refused = attempts.stream().filter(it -> it.state() == Future.State.FAILED).map(Future::exceptionNow).toList();
        assertThat(opened).hasSize(1);
        assertThat(refused).hasSize(ATTEMPTS - 1).allMatch(WalletAlreadyExistsException.class::isInstance);
        assertThat(wallets.get(owner)).isEqualTo(opened.getFirst());
    }
}
