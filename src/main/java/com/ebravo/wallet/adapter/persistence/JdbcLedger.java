package com.ebravo.wallet.adapter.persistence;

import com.ebravo.wallet.application.Ledger;
import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.ExternalEntry;
import com.ebravo.wallet.domain.LedgerEntry;
import com.ebravo.wallet.domain.LedgerTransaction;
import com.ebravo.wallet.domain.Money;
import com.ebravo.wallet.domain.TransactionId;
import com.ebravo.wallet.domain.TransactionType;
import com.ebravo.wallet.domain.WalletEntry;
import com.ebravo.wallet.domain.WalletId;
import com.ebravo.wallet.domain.WalletTransaction;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Plain SQL: ledger rows are only ever inserted and read, so they need no entity lifecycle. */
@Component
class JdbcLedger implements Ledger {

    private final JdbcClient jdbc;

    JdbcLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(LedgerTransaction transaction) {
        jdbc.sql("""
                        insert into ledger_transaction (id, type, correlation_id, created_at)
                        values (:id, :type, :correlationId, :createdAt)
                        """)
                .param("id", transaction.id().value())
                .param("type", transaction.type().name())
                .param("correlationId", transaction.correlationId().value())
                .param("createdAt", transaction.createdAt().atOffset(ZoneOffset.UTC))
                .update();
        transaction.entries().forEach(entry -> append(transaction.id(), entry));
    }

    private void append(TransactionId transaction, LedgerEntry entry) {
        var row = switch (entry) {
            case WalletEntry own -> new EntryRow(own.wallet().value(), own.amount(), own.balanceAfter());
            case ExternalEntry external -> new EntryRow(null, external.amount(), null);
        };
        jdbc.sql("""
                        insert into ledger_entry (transaction_id, wallet_id, amount, balance_after)
                        values (:transaction, :wallet, :amount, :balanceAfter)
                        """)
                .param("transaction", transaction.value())
                .param("wallet", row.wallet(), Types.OTHER)
                .param("amount", row.amount().amount())
                .param("balanceAfter", row.balanceAfter() == null ? null : row.balanceAfter().amount(), Types.NUMERIC)
                .update();
    }

    @Override
    public Optional<WalletTransaction> find(WalletId wallet, TransactionId id) {
        return jdbc.sql("""
                        select t.id, t.type, t.correlation_id, t.created_at, e.amount, e.balance_after
                        from ledger_transaction t
                        join ledger_entry e on e.transaction_id = t.id
                        where t.id = :id and e.wallet_id = :wallet
                        """)
                .param("id", id.value())
                .param("wallet", wallet.value())
                .query((rs, _) -> new WalletTransaction(
                        new TransactionId(rs.getObject("id", UUID.class)),
                        TransactionType.valueOf(rs.getString("type")),
                        new Money(rs.getBigDecimal("amount")),
                        new Money(rs.getBigDecimal("balance_after")),
                        Optional.empty(),
                        new CorrelationId(rs.getString("correlation_id")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private record EntryRow(UUID wallet, Money amount, Money balanceAfter) {
    }
}
