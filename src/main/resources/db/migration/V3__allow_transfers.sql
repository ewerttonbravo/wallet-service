-- A transfer is one ledger transaction with an entry on each wallet and none on the external account.
alter table ledger_transaction drop constraint ck_ledger_transaction_type;
alter table ledger_transaction add constraint ck_ledger_transaction_type check (type in ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER'));
