package com.ebravo.wallet.application;

import com.ebravo.wallet.domain.TransactionType;
import java.util.ArrayList;
import java.util.List;

/** Keeps what the use cases counted, in order, as {@code "posted DEPOSIT"} or {@code "replayed TRANSFER"}. */
public final class RecordingMetrics implements WalletMetrics {

    public final List<String> counted = new ArrayList<>();

    @Override
    public void posted(TransactionType type) {
        counted.add("posted " + type);
    }

    @Override
    public void replayed(TransactionType type) {
        counted.add("replayed " + type);
    }
}
