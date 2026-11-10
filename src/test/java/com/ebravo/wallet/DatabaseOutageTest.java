package com.ebravo.wallet;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The database stops under a running service. It is a database of this class's own, in a context
 * of its own: stopping the shared one would break every other integration test.
 */
@SpringBootTest
@ContextConfiguration(initializers = LocalIssuerInitializer.class)
@AutoConfigureMockMvc
@Import({DatabaseOutageTest.OwnDatabase.class, ApiContractValidation.class})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseOutageTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private PostgreSQLContainer database;

    @BeforeAll
    void stopTheDatabase() {
        database.stop();
    }

    /** Liveness still answers, so the server is up: only readiness follows the database. */
    @Test
    void readinessGoesDownWhileLivenessStaysUp() {
        assertThat(mvc.get().uri("/readyz"))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyJson().extractingPath("$.status").isEqualTo("DOWN");
        assertThat(mvc.get().uri("/livez"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("UP");
    }

    /**
     * A read fails to get a connection, a posting fails to start its transaction: both are the
     * database out of reach, not a wallet too busy to lock, and the body tells nothing of the pool.
     */
    @ParameterizedTest
    @MethodSource("callsThatNeedTheDatabase")
    void answersServiceUnavailableWhenTheDatabaseCannotBeReached(Function<MockMvcTester, MockMvcRequestBuilder> call) {
        var response = call.apply(mvc).with(jwt().jwt(token -> token.subject(anOwner().value()))).exchange();

        assertThat(response)
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasHeader(HttpHeaders.RETRY_AFTER, "1")
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.detail").isEqualTo("The service cannot reach its database, retry the request");
        assertThat(response).bodyText().doesNotContainIgnoringCase("hikari").doesNotContainIgnoringCase("jdbc");
    }

    static Stream<Named<Function<MockMvcTester, MockMvcRequestBuilder>>> callsThatNeedTheDatabase() {
        return Stream.of(
                Named.named("a read", mvc -> mvc.get().uri("/wallets/me")),
                Named.named("a posting", mvc -> mvc.post().uri("/wallets/me/deposits")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"10.00\"}")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OwnDatabase {

        @Bean
        @ServiceConnection
        PostgreSQLContainer database() {
            return new PostgreSQLContainer("postgres:18.6-alpine3.24");
        }
    }
}
