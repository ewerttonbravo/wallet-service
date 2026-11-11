package com.ebravo.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
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
 * The database stops answering without closing its connections, as a frozen host or a stuck disk
 * does: its container is paused, so connections stay open and nothing comes back on them. The
 * database cannot end its own transactions then, so only the service's side can give up. A database
 * of this class's own, in a context of its own, like {@link DatabaseOutageTest}.
 */
@SpringBootTest
@ContextConfiguration(initializers = LocalIssuerInitializer.class)
@AutoConfigureMockMvc
@Import({DatabaseHangTest.OwnDatabase.class, ApiContractValidation.class})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseHangTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private PostgreSQLContainer database;

    @Autowired
    private DataSource dataSource;

    /** Each test pauses the database; the next one starts with it answering again. */
    @AfterEach
    void resumeTheDatabase() {
        if (isPaused()) {
            database.getDockerClient().unpauseContainerCmd(database.getContainerId()).exec();
        }
    }

    /**
     * A statement sent on a connection that was working gets no answer: the driver gives up on the
     * silent socket and reports a lost connection, which the service answers 503 (D50). Without a
     * socket timeout it would wait until the test's own timeout.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void endsAStatementTheHungDatabaseNeverAnswers() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("select 1");
            pause();

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> statement.execute("select 1"))
                    .satisfies(failure -> assertThat(failure.getSQLState()).startsWith("08"))
                    .withRootCauseInstanceOf(SocketTimeoutException.class);
        }
    }

    /** Readiness leaves rotation while the database hangs; liveness, which leaves it out, stays up. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void readinessGoesDownWhileTheDatabaseHangs() {
        pause();

        assertThat(mvc.get().uri("/readyz"))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyJson().extractingPath("$.status").isEqualTo("DOWN");
        assertThat(mvc.get().uri("/livez")).hasStatusOk();
    }

    private void pause() {
        if (!isPaused()) {
            database.getDockerClient().pauseContainerCmd(database.getContainerId()).exec();
        }
    }

    private boolean isPaused() {
        var state = database.getDockerClient().inspectContainerCmd(database.getContainerId()).exec().getState();
        return Boolean.TRUE.equals(state.getPaused());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OwnDatabase {

        @Bean
        @ServiceConnection
        PostgreSQLContainer database() {
            return new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);
        }
    }
}
