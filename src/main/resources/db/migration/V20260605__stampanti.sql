create table if not exists stampanti (
    id bigserial primary key,
    ristorante_id bigint not null references ristoratore(id),
    nome varchar(120) not null,
    modello varchar(40) not null,
    tipo_connessione varchar(30) not null,
    ip_address varchar(64),
    porta integer,
    abilitata boolean not null default true,
    data_creazione timestamp not null default now()
);

create index if not exists idx_stampante_ristorante on stampanti(ristorante_id);
create index if not exists idx_stampante_ristorante_abilitata on stampanti(ristorante_id, abilitata);
