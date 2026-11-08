package com.ebravo.wallet.adapter.web;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.domain.OwnerId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class LockTimeoutApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private DataSource dataSource;

    /**
     * Another transaction holds the wallet's row. The deposit gives up instead of waiting forever,
     * and succeeds once the row is released: so the refusal came from the lock, and changed nothing.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void answersServiceUnavailableWhileTheWalletStaysLocked() throws SQLException {
        var owner = anOwner();
        assertThat(mvc.post().uri("/wallets").with(tokenOf(owner))).hasStatus(HttpStatus.CREATED);

        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockWalletOf(owner, holder);

            assertThat(deposit(owner))
                    .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .hasHeader(HttpHeaders.RETRY_AFTER, "1")
                    .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson().extractingPath("$.detail").isEqualTo("The wallet is busy, retry the request");
            holder.rollback();
        }

        assertThat(deposit(owner)).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.balance").isEqualTo("10.00");
    }

    private static void lockWalletOf(OwnerId owner, Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("select id from wallet where owner_id = ? for update")) {
            statement.setString(1, owner.value());
            assertThat(statement.executeQuery().next()).isTrue();
        }
    }

    private MvcTestResult deposit(OwnerId owner) {
        return mvc.post().uri("/wallets/me/deposits").with(tokenOf(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": \"10.00\"}").exchange();
    }

    private static RequestPostProcessor tokenOf(OwnerId owner) {
        return jwt().jwt(token -> token.subject(owner.value()));
    }
}
