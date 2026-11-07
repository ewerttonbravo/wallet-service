create table wallet (
    id          uuid           primary key,
    owner_id    text           not null,
    balance     numeric(19, 2) not null,
    created_at  timestamptz    not null,
    constraint uk_wallet_owner unique (owner_id)
);
