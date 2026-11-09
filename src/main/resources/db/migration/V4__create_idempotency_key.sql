-- The key a wallet's owner sent with a posting, so that a retry gets the same posting back. Kept as
-- long as the transaction it names, which the ledger keeps forever.
create table idempotency_key (
    wallet_id       uuid not null,
    key             text not null,
    transaction_id  uuid not null,
    constraint pk_idempotency_key primary key (wallet_id, key),
    constraint uk_idempotency_key_transaction unique (transaction_id),
    -- Through the wallet's own entry: a key can only name a transaction that touches its wallet.
    constraint fk_idempotency_key_entry foreign key (transaction_id, wallet_id)
        references ledger_entry (transaction_id, wallet_id)
);

create trigger idempotency_key_append_only before update or delete on idempotency_key
    for each row execute function reject_ledger_change();
create trigger idempotency_key_no_truncate before truncate on idempotency_key
    for each statement execute function reject_ledger_change();
