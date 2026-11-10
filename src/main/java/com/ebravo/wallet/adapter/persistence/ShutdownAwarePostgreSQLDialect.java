package com.ebravo.wallet.adapter.persistence;

import java.util.Set;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.exception.JDBCConnectionException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;

/**
 * PostgreSQL's dialect, reading the server ending a connection as a lost connection. It reads a
 * lost socket (class 08) so already, but not the states PostgreSQL ends connections with on a
 * restart, a crash or a failover, which it reports as generic errors; Spring then answers them as
 * a failure of the service, where a lost connection is a database that is unavailable.
 */
public final class ShutdownAwarePostgreSQLDialect extends PostgreSQLDialect {

    private static final Set<String> SERVER_ENDED_THE_CONNECTION = Set.of("57P01", "57P02", "57P03");

    public ShutdownAwarePostgreSQLDialect(DialectResolutionInfo info) {
        super(info);
    }

    ShutdownAwarePostgreSQLDialect(DatabaseVersion version) {
        super(version);
    }

    @Override
    public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
        var postgres = super.buildSQLExceptionConversionDelegate();
        return (failure, message, sql) -> SERVER_ENDED_THE_CONNECTION.contains(failure.getSQLState())
                ? new JDBCConnectionException(message, failure, sql)
                : postgres.convert(failure, message, sql);
    }
}
