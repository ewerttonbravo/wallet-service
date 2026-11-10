package com.ebravo.wallet;

import static com.ebravo.wallet.Fixtures.anOwner;
import static com.ebravo.wallet.LocalIssuer.AUDIENCE;
import static org.assertj.core.api.Assertions.assertThat;

import com.ebravo.wallet.domain.OwnerId;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The service stops while requests are in flight, as a deploy or a scale-in stops it. Each test
 * closes a service of its own, so it starts one outside Spring Test's context cache, against a
 * database of this class's own. Requests go over plain sockets, so a test chooses which connection
 * each one travels on.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class GracefulShutdownTest {

    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    private final OwnerId owner = anOwner();
    private ConfigurableApplicationContext service;
    private int port;
    private Hold hold;
    private String token;

    @BeforeAll
    static void startTheDatabase() {
        DATABASE.start();
    }

    @AfterAll
    static void stopTheDatabase() {
        DATABASE.stop();
    }

    @BeforeEach
    void startTheServiceWithAWallet() {
        service = new SpringApplicationBuilder(WalletServiceApplication.class, Hold.class)
                .initializers(new LocalIssuerInitializer())
                .properties(
                        "server.port=0",
                        "management.server.port=0",
                        "spring.datasource.url=" + DATABASE.getJdbcUrl(),
                        "spring.datasource.username=" + DATABASE.getUsername(),
                        "spring.datasource.password=" + DATABASE.getPassword())
                .run();
        port = ((WebServerApplicationContext) service).getWebServer().getPort();
        hold = service.getBean(Hold.class);
        token = service.getBean(LocalIssuer.class).tokenFor(owner, AUDIENCE);
        openTheWallet();
    }

    @AfterEach
    void closeTheService() {
        hold.release();
        service.close();
    }

    @Test
    void finishesAPostingInFlightWhenShutdownBegins() throws Exception {
        var deposit = CompletableFuture.supplyAsync(() -> sendOnANewConnection(deposit("10.00", true)));
        hold.awaitArrival();

        var shutdown = beginShutdown();
        awaitConnectionsRefused();
        assertThat(shutdown).as("the shutdown waits for the posting").isNotDone();
        hold.release();

        assertThat(deposit.get()).hasValueSatisfying(response -> assertThat(response.status()).isEqualTo(201));
        shutdown.get();
        assertThat(balanceInTheDatabase()).isEqualByComparingTo("10.00");
    }

    /** A client that still routes to the instance gets a refusal at once, and turns to another instance. */
    @Test
    void refusesNewConnectionsWhileDraining() throws Exception {
        try (var accepted = new Socket("localhost", port)) {
            assertThat(accepted.isConnected()).as("the port accepts connections before the shutdown").isTrue();
        }
        CompletableFuture.supplyAsync(() -> sendOnANewConnection(deposit("10.00", true)));
        hold.awaitArrival();

        var shutdown = beginShutdown();

        awaitConnectionsRefused();
        assertThat(shutdown).as("refused while draining, not once stopped").isNotDone();
    }

    private CompletableFuture<Void> beginShutdown() {
        return CompletableFuture.runAsync(service::close);
    }

    /** The server stops accepting connections a few milliseconds after the shutdown begins. */
    private void awaitConnectionsRefused() {
        var deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            try (var connection = new Socket("localhost", port)) {
                Thread.onSpinWait();
            } catch (IOException refused) {
                return;
            }
        }
        throw new AssertionError("the service still accepts connections");
    }

    /**
     * The first token check of a new service reads the issuer's keys, which a cold JVM can take
     * longer than the two seconds allowed to do (D52); a retry finds them read.
     */
    private void openTheWallet() {
        for (var attempt = 0; attempt < 3; attempt++) {
            var opened = sendOnANewConnection(request("POST", "/wallets", "", false)).orElseThrow();
            if (opened.status() == 201) {
                return;
            }
            assertThat(opened.status()).isEqualTo(503);
        }
        throw new AssertionError("the wallet could not be opened");
    }

    private BigDecimal balanceInTheDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
                var query = connection.prepareStatement("select balance from wallet where owner_id = ?")) {
            query.setString(1, owner.value());
            try (var result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBigDecimal(1);
            }
        }
    }

    private String deposit(String amount, boolean held) {
        return request("POST", "/wallets/me/deposits", "{\"amount\": \"%s\"}".formatted(amount), held);
    }

    private String request(String method, String path, String body, boolean held) {
        var request = new StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: localhost\r\n")
                .append("Authorization: Bearer ").append(token).append("\r\n")
                .append("Idempotency-Key: ").append(UUID.randomUUID()).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
        if (held) {
            request.append(Hold.HEADER).append(": yes\r\n");
        }
        return request.append("\r\n").append(body).toString();
    }

    private Optional<Response> sendOnANewConnection(String request) {
        try (var connection = new Socket("localhost", port)) {
            return send(connection, request);
        } catch (IOException refused) {
            throw new AssertionError("the service refused the connection", refused);
        }
    }

    /** Sends one request on the connection and reads its response, or empty when the server closes it unanswered. */
    private static Optional<Response> send(Socket connection, String request) {
        try {
            connection.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            return Response.read(new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)));
        } catch (IOException closed) {
            return Optional.empty();
        }
    }

    private record Response(int status, Map<String, String> headers, String body) {

        static Optional<Response> read(BufferedReader in) throws IOException {
            var statusLine = in.readLine();
            if (statusLine == null) {
                return Optional.empty();
            }
            var headers = new LinkedHashMap<String, String>();
            for (var line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                var colon = line.indexOf(':');
                headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            var body = "chunked".equals(headers.get("transfer-encoding"))
                    ? chunked(in)
                    : read(in, Integer.parseInt(headers.getOrDefault("content-length", "0")));
            return Optional.of(new Response(Integer.parseInt(statusLine.split(" ")[1]), headers, body));
        }

        private static String chunked(BufferedReader in) throws IOException {
            var body = new StringBuilder();
            for (var size = Integer.parseInt(in.readLine().trim(), 16); size > 0; size = Integer.parseInt(in.readLine().trim(), 16)) {
                body.append(read(in, size));
                in.readLine();
            }
            in.readLine();
            return body.toString();
        }

        private static String read(BufferedReader in, int length) throws IOException {
            var chars = new char[length];
            for (var read = 0; read < length; ) {
                read += in.read(chars, read, length - read);
            }
            return new String(chars);
        }
    }

    /**
     * Holds a request that carries {@link #HEADER} until the test releases it. It runs after the
     * token check and before the controller, so the whole posting happens after the release.
     */
    static final class Hold implements Filter {

        static final String HEADER = "X-Test-Hold";

        private final CountDownLatch arrived = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            if (((HttpServletRequest) request).getHeader(HEADER) != null) {
                arrived.countDown();
                try {
                    released.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            chain.doFilter(request, response);
        }

        void awaitArrival() throws InterruptedException {
            assertThat(arrived.await(5, TimeUnit.SECONDS)).as("the held request arrived").isTrue();
        }

        void release() {
            released.countDown();
        }
    }
}
