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
import org.junit.jupiter.api.Timeout;

/**
 * Socket timeouts bound each connect and each read, not their sum, and nothing bounds the name
 * lookup: a whole read of the key set must end within one deadline, whatever step it is stuck at.
 */
class DeadlineRetrieverTest {

    private static final Duration DEADLINE = Duration.ofMillis(200);
    // For the reads that answer at once: any deadline they could miss would make the tests race the clock.
    private static final Duration AMPLE = Duration.ofMinutes(1);
    private static final URL KEY_SET = url("http://issuer/local/jwks");

    /** The read never ends, so only the deadline ends the wait; the timeout bounds a retriever without one. */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
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

        assertThatThrownBy(() -> retriever.retrieveResource(KEY_SET)).isInstanceOf(IOException.class);

        // The stuck read is stopped too, so it does not go on holding a connection.
        interrupted.await();
    }

    /** Guards the timeout above: a read within the deadline is answered as it is. */
    @Test
    void returnsWhatIsReadWithinTheDeadline() throws Exception {
        var keys = new Resource("{\"keys\":[]}", "application/json");
        var retriever = new DeadlineRetriever(url -> keys, AMPLE);

        assertThat(retriever.retrieveResource(KEY_SET)).isSameAs(keys);
    }

    @Test
    void passesOnTheFailureOfTheRead() {
        var refused = new IOException("Connection refused");
        var retriever = new DeadlineRetriever(url -> {
            throw refused;
        }, AMPLE);

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
