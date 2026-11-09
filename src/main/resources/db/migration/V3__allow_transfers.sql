-- A transfer is one ledger transaction with an entry on each wallet and none on the external account.
alter table ledger_transaction drop constraint ck_ledger_transaction_type;
alter table ledger_transaction add constraint ck_ledger_transaction_type check (type in ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER'));

-- Checked at commit, with the balance, once every entry is in place: money enters or leaves through
-- the external account only in deposits and withdrawals, and only in the direction their type names.
create or replace function assert_ledger_transaction_balanced(transaction uuid) returns void language plpgsql as $$
declare
    kind     text;
    entries  integer;
    total    numeric;
    wallets  integer;
    credited integer;
begin
    select count(*), coalesce(sum(amount), 0), count(wallet_id), count(*) filter (where wallet_id is not null and amount > 0)
        into entries, total, wallets, credited
        from ledger_entry where transaction_id = transaction;
    if entries < 2 or total <> 0 then
        raise exception 'Ledger transaction % must have at least two entries summing to zero, has % summing to %',
            transaction, entries, total
            using errcode = 'integrity_constraint_violation';
    end if;
    select type into kind from ledger_transaction where id = transaction;
    if not (entries = 2 and case kind
                when 'DEPOSIT' then wallets = 1 and credited = 1
                when 'WITHDRAWAL' then wallets = 1 and credited = 0
                when 'TRANSFER' then wallets = 2
            end) then
        raise exception 'Ledger transaction % does not fit its type %: % entries, % on wallets, % crediting a wallet',
            transaction, kind, entries, wallets, credited
            using errcode = 'integrity_constraint_violation';
    end if;
end
$$;
