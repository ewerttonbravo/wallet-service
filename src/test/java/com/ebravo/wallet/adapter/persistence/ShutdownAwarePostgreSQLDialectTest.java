package com.ebravo.wallet.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.JDBCException;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Reads PostgreSQL ending a connection as a lost connection, and every other failure as PostgreSQL's dialect does. */
class ShutdownAwarePostgreSQLDialectTest {

    private static final DatabaseVersion VERSION = DatabaseVersion.make(18);

    private final ShutdownAwarePostgreSQLDialect dialect = new ShutdownAwarePostgreSQLDialect(VERSION);
    private final PostgreSQLDialect postgres = new PostgreSQLDialect(VERSION);

    /** On a restart, a crash or a failover, PostgreSQL ends connections with one of these. */
    @ParameterizedTest
    @ValueSource(strings = {"57P01", "57P02", "57P03"})
    void readsTheServerEndingAConnectionAsALostConnection(String sqlState) {
        var failure = new SQLException("terminating connection", sqlState);

        assertThat(dialect.buildSQLExceptionConversionDelegate().convert(failure, "query", "select 1"))
                .isInstanceOf(JDBCConnectionException.class)
                .hasCause(failure);
    }

    /** A deadlock and a lock timeout stay lock failures, a busy wallet; a violation keeps its constraint. */
    @ParameterizedTest
    @ValueSource(strings = {"40P01", "55P03", "57014", "23505", "08006"})
    void readsEveryOtherFailureAsPostgreSQLDoes(String sqlState) {
        var failure = new SQLException("refused", sqlState);

        assertThat(typeOf(dialect.buildSQLExceptionConversionDelegate().convert(failure, "query", "select 1")))
                .isEqualTo(typeOf(postgres.buildSQLExceptionConversionDelegate().convert(failure, "query", "select 1")));
    }

    private static Class<?> typeOf(JDBCException converted) {
        return converted == null ? null : converted.getClass();
    }
}
