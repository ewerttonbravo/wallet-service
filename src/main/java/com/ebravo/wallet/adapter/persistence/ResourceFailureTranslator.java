package com.ebravo.wallet.adapter.persistence;

import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

/**
 * Spring's reading of a failure, the one {@code JdbcClient} gives the ledger, kept only when it says
 * the database is out of reach: Hibernate reads PostgreSQL's shutdown states (57P01 on a restart or
 * a failover) as generic errors. Every other failure is left to Hibernate, whose reading is finer
 * (a deadlock is a lock failure, a unique violation names its constraint).
 */
final class ResourceFailureTranslator implements SQLExceptionTranslator {

    private final SQLExceptionTranslator spring = new SQLExceptionSubclassTranslator();

    @Override
    public DataAccessException translate(String task, String sql, SQLException failure) {
        var translated = spring.translate(task, sql, failure);
        return translated instanceof DataAccessResourceFailureException
                || translated instanceof TransientDataAccessResourceException ? translated : null;
    }
}
