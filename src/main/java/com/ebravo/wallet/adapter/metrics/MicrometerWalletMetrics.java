package com.ebravo.wallet.adapter.metrics;

import com.ebravo.wallet.application.WalletMetrics;
import com.ebravo.wallet.domain.TransactionType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code wallet.postings}, by {@code type} and {@code outcome} ({@code posted} or {@code replayed}).
 * Every series is registered at startup, at zero, so a rate over it exists before the first posting.
 */
@Component
class MicrometerWalletMetrics implements WalletMetrics {

    private final Map<TransactionType, Counter> posted = new EnumMap<>(TransactionType.class);
    private final Map<TransactionType, Counter> replayed = new EnumMap<>(TransactionType.class);

    MicrometerWalletMetrics(MeterRegistry registry) {
        for (var type : TransactionType.values()) {
            posted.put(type, counter(registry, type, "posted"));
            replayed.put(type, counter(registry, type, "replayed"));
        }
    }

    @Override
    public void posted(TransactionType type) {
        posted.get(type).increment();
    }

    @Override
    public void replayed(TransactionType type) {
        replayed.get(type).increment();
    }

    private static Counter counter(MeterRegistry registry, TransactionType type, String outcome) {
        return Counter.builder("wallet.postings")
                .description("Postings answered, by type, posted or replayed from their idempotency key")
                .tag("type", type.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome)
                .register(registry);
    }
}
