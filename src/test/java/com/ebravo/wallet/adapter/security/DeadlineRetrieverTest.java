package com.ebravo.wallet.adapter.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.util.Resource;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Socket timeouts bound each connect and each read, not their sum, and nothing bounds the name
 * lookup: a whole read of the key set must end within one deadline, whatever step it is stuck at.
 */
class DeadlineRetrieverTest {

    private static final Duration DEADLINE = Duration.ofMillis(200);
    private static final URL KEY_SET = url("http://issuer/local/jwks");

    @Test
    void givesUpOnAReadStuckPastTheDeadline() throws Exception {
        var interrupted = new CountDownLatch(1);
        var retriever = new DeadlineRetriever(url -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return null;
        }, DEADLINE);

        var started = System.nanoTime();
        assertThatThrownBy(() -> retriever.retrieveResource(KEY_SET)).isInstanceOf(IOException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(DEADLINE.multipliedBy(5));
        // The stuck read is stopped too, so it does not go on holding a connection.
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    /** Guards the timeout above: a read within the deadline is answered as it is. */
    @Test
    void returnsWhatIsReadWithinTheDeadline() throws Exception {
        var keys = new Resource("{\"keys\":[]}", "application/json");
        var retriever = new DeadlineRetriever(url -> keys, DEADLINE);

        assertThat(retriever.retrieveResource(KEY_SET)).isSameAs(keys);
    }

    @Test
    void passesOnTheFailureOfTheRead() {
        var refused = new IOException("Connection refused");
        var retriever = new DeadlineRetriever(url -> {
            throw refused;
        }, DEADLINE);

        assertThatThrownBy(() -> retriever.retrieveResource(KEY_SET)).isSameAs(refused);
    }

    private static URL url(String value) {
        try {
            return URI.create(value).toURL();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
