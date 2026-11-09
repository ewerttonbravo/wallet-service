package com.ebravo.wallet.adapter.persistence;

import com.ebravo.wallet.application.Ledger;
import com.ebravo.wallet.application.PageRequest;
import com.ebravo.wallet.application.TransactionPage;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
                        select t.id, t.type, t.correlation_id, t.created_at, e.amount, e.balance_after,
                               other.wallet_id as counterparty
                        from ledger_transaction t
                        join ledger_entry e on e.transaction_id = t.id
                        -- The other wallet of a transfer; the external account (null wallet) never matches.
                        left join ledger_entry other on other.transaction_id = t.id and other.wallet_id <> e.wallet_id
                        where t.id = :id and e.wallet_id = :wallet
                        """)
                .param("id", id.value())
                .param("wallet", wallet.value())
                .query((rs, _) -> walletTransaction(rs))
                .optional();
    }

    /**
     * One statement, so the total and the page come from the same snapshot even while postings
     * commit. A page past the end still yields the count row, with no transaction on it. The wallet
     * has one entry per transaction, whose identity key is the order it was posted in.
     */
    @Override
    public TransactionPage history(WalletId wallet, PageRequest page) {
        return jdbc.sql("""
                        select c.total, p.*
                        from (select count(*) as total from ledger_entry where wallet_id = :wallet) c
                        left join lateral (
                            select e.id as entry_id, t.id, t.type, t.correlation_id, t.created_at, e.amount,
                                   e.balance_after, other.wallet_id as counterparty
                            from ledger_entry e
                            join ledger_transaction t on t.id = e.transaction_id
                            left join ledger_entry other
                                on other.transaction_id = e.transaction_id and other.wallet_id <> e.wallet_id
                            where e.wallet_id = :wallet
                            order by e.id desc
                            offset :offset limit :size) p on true
                        -- The join does not promise to keep the order of the lateral subquery.
                        order by p.entry_id desc
                        """)
                .param("wallet", wallet.value())
                .param("offset", page.offset())
                .param("size", page.size())
                .query(rs -> {
                    var transactions = new ArrayList<WalletTransaction>();
                    var total = 0L;
                    while (rs.next()) {
                        total = rs.getLong("total");
                        if (rs.getObject("entry_id") != null) {
                            transactions.add(walletTransaction(rs));
                        }
                    }
                    return new TransactionPage(transactions, page, total);
                });
    }

    /** Walks the wallet's entries back from the newest, on its index, to the first one not after the instant. */
    @Override
    public Optional<Money> balanceAt(WalletId wallet, Instant at) {
        return jdbc.sql("""
                        select e.balance_after
                        from ledger_entry e
                        join ledger_transaction t on t.id = e.transaction_id
                        where e.wallet_id = :wallet and t.created_at <= :at
                        order by e.id desc
                        limit 1
                        """)
                .param("wallet", wallet.value())
                .param("at", at.atOffset(ZoneOffset.UTC))
                .query((rs, _) -> new Money(rs.getBigDecimal("balance_after")))
                .optional();
    }

    private static WalletTransaction walletTransaction(ResultSet rs) throws SQLException {
        return new WalletTransaction(
                new TransactionId(rs.getObject("id", UUID.class)),
                TransactionType.valueOf(rs.getString("type")),
                new Money(rs.getBigDecimal("amount")),
                new Money(rs.getBigDecimal("balance_after")),
                Optional.ofNullable(rs.getObject("counterparty", UUID.class)).map(WalletId::new),
                new CorrelationId(rs.getString("correlation_id")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private record EntryRow(UUID wallet, Money amount, Money balanceAfter) {
    }
}
