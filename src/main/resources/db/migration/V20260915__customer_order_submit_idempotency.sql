create table if not exists customer_order_submission (
    id bigserial primary key,
    ristoratore_id bigint not null references ristoratore(id),
    table_id integer not null,
    request_id varchar(36) not null,
    payload_hash varchar(64) not null,
    ordine_id bigint not null references customer_orders(id) on delete cascade,
    created_at timestamp(6) without time zone not null default current_timestamp,
    constraint uk_customer_order_submission_intent unique (ristoratore_id, table_id, request_id)
);

create index if not exists idx_customer_order_submission_created_at
    on customer_order_submission(created_at);
