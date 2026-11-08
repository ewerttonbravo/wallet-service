-- Last line of defense: no code path may leave a wallet below zero.
alter table wallet add constraint ck_wallet_balance_non_negative check (balance >= 0);

create table ledger_transaction (
    id              uuid        primary key,
    type            text        not null,
    correlation_id  text        not null,
    created_at      timestamptz not null,
    constraint ck_ledger_transaction_type check (type in ('DEPOSIT', 'WITHDRAWAL'))
);

-- A null wallet_id is the external account (money outside the service). It keeps no running
-- balance: that would be one row locked by every posting of every user.
create table ledger_entry (
    id              bigint         generated always as identity primary key,
    transaction_id  uuid           not null references ledger_transaction (id),
    wallet_id       uuid           references wallet (id),
    amount          numeric(19, 2) not null,
    balance_after   numeric(19, 2),
    constraint ck_ledger_entry_amount_non_zero check (amount <> 0),
    -- "is not null" is spelled out: a check that evaluates to null passes.
    constraint ck_ledger_entry_balance check (
        (wallet_id is null and balance_after is null)
        or (wallet_id is not null and balance_after is not null and balance_after >= 0))
);

create index ix_ledger_entry_transaction on ledger_entry (transaction_id);
create index ix_ledger_entry_wallet on ledger_entry (wallet_id, id);

create function reject_ledger_change() returns trigger language plpgsql as $$
begin
    raise exception 'The ledger is append-only: % on % is not allowed', tg_op, tg_table_name
        using errcode = 'integrity_constraint_violation';
end
$$;

create trigger ledger_transaction_append_only before update or delete on ledger_transaction
    for each row execute function reject_ledger_change();
create trigger ledger_transaction_no_truncate before truncate on ledger_transaction
    for each statement execute function reject_ledger_change();
create trigger ledger_entry_append_only before update or delete on ledger_entry
    for each row execute function reject_ledger_change();
create trigger ledger_entry_no_truncate before truncate on ledger_entry
    for each statement execute function reject_ledger_change();

-- Double entry, checked at commit when every entry of the transaction is in place.
create function assert_ledger_transaction_balanced(transaction uuid) returns void language plpgsql as $$
declare
    entries integer;
    total   numeric;
begin
    select count(*), coalesce(sum(amount), 0) into entries, total from ledger_entry where transaction_id = transaction;
    if entries < 2 or total <> 0 then
        raise exception 'Ledger transaction % must have at least two entries summing to zero, has % summing to %',
            transaction, entries, total
            using errcode = 'integrity_constraint_violation';
    end if;
end
$$;

create function check_new_ledger_transaction() returns trigger language plpgsql as $$
begin
    perform assert_ledger_transaction_balanced(new.id);
    return null;
end
$$;

create function check_new_ledger_entry() returns trigger language plpgsql as $$
begin
    perform assert_ledger_transaction_balanced(new.transaction_id);
    return null;
end
$$;

create constraint trigger ledger_transaction_balanced after insert on ledger_transaction
    deferrable initially deferred for each row execute function check_new_ledger_transaction();
create constraint trigger ledger_entry_balanced after insert on ledger_entry
    deferrable initially deferred for each row execute function check_new_ledger_entry();
