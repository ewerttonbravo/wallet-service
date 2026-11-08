package com.ebravo.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WalletTransactionTest {

    @Test
    void keepsEveryFieldAsGiven() {
        var id = TransactionId.newId();
        var correlation = new CorrelationId("request-1");
        var at = Instant.parse("2026-11-08T10:15:30.123456Z");

        var transaction = new WalletTransaction(
                id, TransactionType.WITHDRAWAL, new Money(new BigDecimal("-1.00")), new Money(new BigDecimal("9.00")), correlation, at);

        assertThat(transaction.id()).isEqualTo(id);
        assertThat(transaction.type()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(transaction.amount()).isEqualTo(new Money(new BigDecimal("-1.00")));
        assertThat(transaction.balance()).isEqualTo(new Money(new BigDecimal("9.00")));
        assertThat(transaction.correlationId()).isEqualTo(correlation);
        assertThat(transaction.createdAt()).isEqualTo(at);
    }
}
