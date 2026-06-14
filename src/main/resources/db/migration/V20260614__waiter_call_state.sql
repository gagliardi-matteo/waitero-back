alter table if exists tavoli
    add column if not exists waiter_call_pending boolean not null default false;

alter table if exists tavoli
    add column if not exists waiter_called_at timestamp(6) without time zone;

update tavoli
set waiter_call_pending = false
where waiter_call_pending is null;
