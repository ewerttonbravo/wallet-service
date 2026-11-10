package com.ebravo.wallet.adapter.security;

import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads a resource within one deadline for the whole read: name lookup, connect and every read
 * together. Socket timeouts bound each step alone, and nothing bounds the lookup. The read runs on
 * a virtual thread, whose blocking socket calls end when it is interrupted at the deadline.
 */
final class DeadlineRetriever implements ResourceRetriever {

    private final ResourceRetriever delegate;
    private final Duration deadline;

    DeadlineRetriever(ResourceRetriever delegate, Duration deadline) {
        this.delegate = delegate;
        this.deadline = deadline;
    }

    @Override
    public Resource retrieveResource(URL url) throws IOException {
        var read = new FutureTask<>(() -> delegate.retrieveResource(url));
        Thread.ofVirtual().start(read);
        try {
            return read.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            read.cancel(true);
            throw new IOException("Not read within " + deadline + ": " + url, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException failure) {
                throw failure;
            }
            throw new IOException(e.getCause());
        } catch (InterruptedException e) {
            read.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while reading " + url);
        }
    }
}
