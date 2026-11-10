package com.ebravo.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
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

    @TestConfiguration(proxyBeanMethods = false)
    static class OwnDatabase {

        @Bean
        @ServiceConnection
        PostgreSQLContainer database() {
            return new PostgreSQLContainer("postgres:18.6-alpine3.24");
        }
    }
}
