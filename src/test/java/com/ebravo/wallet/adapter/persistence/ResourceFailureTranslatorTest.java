package com.ebravo.wallet.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;

/** Takes Spring's reading only when it says the database is out of reach; any other is Hibernate's. */
class ResourceFailureTranslatorTest {

    private final ResourceFailureTranslator translator = new ResourceFailureTranslator();

    /** A lost socket, and PostgreSQL ending connections on a restart, a crash or a failover. */
    @ParameterizedTest
    @ValueSource(strings = {"08006", "57P01", "57P02", "57P03"})
    void readsALostConnectionAsAResourceFailure(String sqlState) {
        assertThat(translator.translate("query", null, new SQLException("gone", sqlState)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void readsAPoolTimeoutAsATransientResourceFailure() {
        assertThat(translator.translate("query", null, new SQLTransientConnectionException("pool timed out", "08001")))
                .isInstanceOf(TransientDataAccessResourceException.class);
    }

    /**
     * Spring reads each of these too, more coarsely than Hibernate: a deadlock or a lock timeout must
     * stay a lock failure, a busy wallet, and a cancelled statement and a unique violation keep what
     * Hibernate tells of them.
     */
    @ParameterizedTest
    @ValueSource(strings = {"40P01", "40001", "55P03", "57014", "23505"})
    void leavesEveryOtherStateToHibernate(String sqlState) {
        assertThat(translator.translate("query", null, new SQLException("refused", sqlState))).isNull();
    }
}
