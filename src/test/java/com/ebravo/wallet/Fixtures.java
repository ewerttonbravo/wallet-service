package com.ebravo.wallet;

import com.ebravo.wallet.domain.IdempotencyKey;
import com.ebravo.wallet.domain.OwnerId;
import java.time.Instant;
import java.util.UUID;

public final class Fixtures {

    /** Microsecond precision, as stored by the database. */
    public static final Instant NOW = Instant.parse("2026-11-07T14:53:35.123456Z");

    private Fixtures() {
    }

    /** A fresh owner per test isolates tests that share a database, without cleanup. */
    public static OwnerId anOwner() {
        return new OwnerId(UUID.randomUUID().toString());
    }

    /** A fresh key per posting: each call stands for a new request, not a retry. */
    public static IdempotencyKey aKey() {
        return new IdempotencyKey(UUID.randomUUID().toString());
    }
}
