alter table ristoratore add column if not exists is_demo boolean not null default false;

create table if not exists demo_session (
    id uuid primary key,
    token_hash varchar(64) not null unique,
    ristoratore_id bigint not null references ristoratore(id),
    table_id bigint not null unique references tavoli(id) on delete cascade,
    created_at timestamp not null,
    expires_at timestamp not null,
    last_activity_at timestamp not null,
    order_count integer not null default 0
);

create index if not exists idx_demo_session_expires_at on demo_session(expires_at);

alter table customer_orders add column if not exists is_demo boolean not null default false;
alter table customer_orders add column if not exists demo_session_id uuid references demo_session(id) on delete cascade;
create index if not exists idx_customer_orders_demo_session on customer_orders(demo_session_id, updated_at desc);
