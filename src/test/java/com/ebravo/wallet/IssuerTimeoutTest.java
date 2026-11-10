package com.ebravo.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The issuer accepts connections and never answers, the outage that costs most: without a bound,
 * each request waits for the read to time out, and concurrent ones queue behind each other's
 * reads. On a real server, so the requests really run side by side.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class IssuerTimeoutTest {

    private static final int REQUESTS = 8;
    /**
     * Two seconds to read the keys, after at most two waiting for another request's read, plus room
     * for a loaded build machine; without the bounds, a request waits 15 seconds or more.
     */
    private static final Duration CEILING = Duration.ofSeconds(6);

    private static final SilentIssuer ISSUER = new SilentIssuer();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void trustTheSilentIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER.uri() + "/local");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> ISSUER.uri() + "/local/jwks");
    }

    @AfterAll
    static void silenceTheIssuer() throws IOException {
        ISSUER.close();
    }

    @Test
    void answersEveryConcurrentRequestWithinTheCeiling() throws Exception {
        var client = HttpClient.newHttpClient();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Answer>> answers = IntStream.range(0, REQUESTS)
                    .mapToObj(i -> executor.submit(() -> {
                        start.await();
                        return send(client);
                    }))
                    .toList();
            start.countDown();

            for (var answer : answers) {
                assertThat(answer.get().status()).isEqualTo(503);
                assertThat(answer.get().took()).isLessThan(CEILING);
            }
        }
        // The guard: the requests did reach the issuer and waited on it, instead of failing early.
        assertThat(ISSUER.connections()).isPositive();
    }

    private Answer send(HttpClient client) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/wallets/me"))
                .header("Authorization", IssuerOutageTest.bearer())
                // Past the ceiling, so a request held longer fails here instead of hanging the build.
                .timeout(CEILING.multipliedBy(2))
                .build();
        var started = System.nanoTime();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        return new Answer(response.statusCode(), Duration.ofNanos(System.nanoTime() - started));
    }

    private record Answer(int status, Duration took) {
    }

    /** Accepts every connection and holds it open without a byte in return. */
    private static final class SilentIssuer implements AutoCloseable {

        private final ServerSocket server;
        private final List<Socket> held = new CopyOnWriteArrayList<>();

        SilentIssuer() {
            try {
                server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        held.add(server.accept());
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
        }

        String uri() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        int connections() {
            return held.size();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (var socket : held) {
                socket.close();
            }
        }
    }
}
