-- WaiterO experiment analytics queries for Metabase or similar BI tools.
-- Required filters:
--   {{ristorante_id}}  -> Number
--   {{start_date}}     -> Date
--   {{end_date}}       -> Date
--
-- Notes:
-- - These queries use the real WaiterO column names:
--     customer_orders.ritoratore_id
--     customer_orders.session_id
--     customer_order_items.ordine_id
--     customer_order_items.prezzo_unitario
-- - Query 1 mirrors the backend ExperimentAnalyticsService logic:
--   metrics are computed from customer_orders only, with normalized variants A/B/C.
-- - Blank session IDs are ignored, matching backend behavior.

-- ============================================================
-- QUERY 1: Core metrics per variant
-- ============================================================
with variants as (
    select 'A' as variant
    union all select 'B'
    union all select 'C'
),
normalized_orders as (
    select
        case
            when upper(coalesce(nullif(btrim(o.variant), ''), '')) = 'A' then 'A'
            when upper(coalesce(nullif(btrim(o.variant), ''), '')) = 'B' then 'B'
            when upper(coalesce(nullif(btrim(o.variant), ''), '')) = 'C' then 'C'
            else null
        end as variant,
        coalesce(o.totale, 0)::numeric(12,2) as totale,
        nullif(btrim(o.session_id), '') as session_id
    from customer_orders o
    where o.ristoratore_id = {{ristorante_id}}
      and o.created_at >= {{start_date}}
      and o.created_at < {{end_date}} + interval '1 day'
),
aggregated as (
    select
        variant,
        count(*)::bigint as total_orders,
        coalesce(sum(totale), 0)::numeric(12,2) as total_revenue,
        count(distinct session_id)::bigint as total_sessions
    from normalized_orders
    where variant is not null
    group by variant
)
select
    v.variant,
    coalesce(a.total_revenue, 0)::numeric(12,2) as total_revenue,
    coalesce(a.total_orders, 0)::bigint as total_orders,
    coalesce(a.total_sessions, 0)::bigint as total_sessions,
    coalesce(round(coalesce(a.total_revenue, 0) / nullif(a.total_sessions, 0), 2), 0)::numeric(12,2) as rps,
    coalesce(round(coalesce(a.total_revenue, 0) / nullif(a.total_orders, 0), 2), 0)::numeric(12,2) as aov,
    coalesce(round(coalesce(a.total_orders, 0)::numeric / nullif(a.total_sessions, 0), 4), 0)::numeric(12,4) as cr
from variants v
left join aggregated a on a.variant = v.variant
order by v.variant;

-- ============================================================
-- QUERY 2: Daily RPS trend by variant
-- ============================================================
with variants as (
    select 'A' as variant
    union all select 'B'
    union all select 'C'
),
days as (
    select generate_series(
        {{start_date}}::date,
        {{end_date}}::date,
        interval '1 day'
    )::date as day
),
normalized_orders as (
    select
        cast(o.created_at as date) as day,
        case
            when upper(coalesce(nullif(btrim(o.variant), ''), '')) = 'A' then 'A'
            when upper(coalesce(nullif(btrim(o.variant), ''), '')) = 'B' then 'B'
            when upper(coalesce(nullif(btrim(o.variant), ''), '')) = 'C' then 'C'
            else null
        end as variant,
        coalesce(o.totale, 0)::numeric(12,2) as totale,
        nullif(btrim(o.session_id), '') as session_id
    from customer_orders o
    where o.ristoratore_id = {{ristorante_id}}
      and o.created_at >= {{start_date}}
      and o.created_at < {{end_date}} + interval '1 day'
),
aggregated as (
    select
        day,
        variant,
        coalesce(sum(totale), 0)::numeric(12,2) as total_revenue,
        count(distinct session_id)::bigint as total_sessions
    from normalized_orders
    where variant is not null
    group by day, variant
)
select
    d.day,
    v.variant,
    coalesce(round(coalesce(a.total_revenue, 0) / nullif(a.total_sessions, 0), 2), 0)::numeric(12,2) as rps
from days d
cross join variants v
left join aggregated a
    on a.day = d.day
   and a.variant = v.variant
order by d.day, v.variant;

-- ============================================================
-- QUERY 3: Top dishes per variant
-- ============================================================
with normalized_orders as (
    select
        o.id,
        case
            when upper(coalesce(nullif(btrim(o.variant), ''), '')) = 'A' then 'A'
            when upper(coalesce(nullif(btrim(o.variant), ''), '')) = 'B' then 'B'
            when upper(coalesce(nullif(btrim(o.variant), ''), '')) = 'C' then 'C'
            else null
        end as variant
    from customer_orders o
    where o.ristoratore_id = {{ristorante_id}}
      and o.created_at >= {{start_date}}
      and o.created_at < {{end_date}} + interval '1 day'
),
dish_sales as (
    select
        o.variant,
        oi.piatto_id,
        max(coalesce(p.nome, oi.nome)) as dish_name,
        max(p.categoria) as categoria,
        count(distinct o.id)::bigint as total_orders,
        coalesce(sum(oi.quantity), 0)::bigint as units_sold,
        coalesce(sum(oi.prezzo_unitario * oi.quantity), 0)::numeric(12,2) as total_revenue
    from normalized_orders o
    join customer_order_items oi on oi.ordine_id = o.id
    left join piatto p on p.id = oi.piatto_id
    where o.variant is not null
    group by o.variant, oi.piatto_id
),
ranked as (
    select
        ds.*,
        row_number() over (
            partition by ds.variant
            order by ds.total_revenue desc, ds.total_orders desc, ds.piatto_id asc
        ) as rank_in_variant
    from dish_sales ds
)
select
    variant,
    rank_in_variant,
    piatto_id,
    dish_name,
    categoria,
    total_orders,
    units_sold,
    total_revenue
from ranked
where rank_in_variant <= 10
order by variant, rank_in_variant;

-- ============================================================
-- QUERY 4: Optional funnel by variant
-- Exposed sessions are based on event_log.view_menu_item joined to experiment_assignment.
-- This is diagnostic and does not replace Query 1 for autopilot parity.
-- ============================================================
with variants as (
    select 'A' as variant
    union all select 'B'
    union all select 'C'
),
assignments as (
    select
        ea.restaurant_id,
        nullif(btrim(ea.session_id), '') as session_id,
        case
            when upper(coalesce(nullif(btrim(ea.variant), ''), '')) = 'A' then 'A'
            when upper(coalesce(nullif(btrim(ea.variant), ''), '')) = 'B' then 'B'
            when upper(coalesce(nullif(btrim(ea.variant), ''), '')) = 'C' then 'C'
            else null
        end as variant
    from experiment_assignment ea
    where ea.restaurant_id = {{ristorante_id}}
),
exposed_sessions as (
    select distinct
        a.variant,
        el.session_id
    from event_log el
    join assignments a
      on a.restaurant_id = el.restaurant_id
     and a.session_id = nullif(btrim(el.session_id), '')
    where el.restaurant_id = {{ristorante_id}}
      and el.created_at >= {{start_date}}
      and el.created_at < {{end_date}} + interval '1 day'
      and el.event_type = 'view_menu_item'
      and a.variant is not null
),
ordered_sessions as (
    select
        case
            when upper(coalesce(nullif(btrim(o.variant), ''), '')) = 'A' then 'A'
            when upper(coalesce(nullif(btrim(o.variant), ''), '')) = 'B' then 'B'
            when upper(coalesce(nullif(btrim(o.variant), ''), '')) = 'C' then 'C'
            else null
        end as variant,
        nullif(btrim(o.session_id), '') as session_id,
        o.id,
        coalesce(o.totale, 0)::numeric(12,2) as totale
    from customer_orders o
    where o.ristoratore_id = {{ristorante_id}}
      and o.created_at >= {{start_date}}
      and o.created_at < {{end_date}} + interval '1 day'
)
select
    v.variant,
    count(distinct es.session_id)::bigint as exposed_sessions,
    count(distinct os.session_id)::bigint as ordering_sessions,
    count(os.id)::bigint as total_orders,
    coalesce(sum(os.totale), 0)::numeric(12,2) as total_revenue,
    coalesce(round(count(distinct os.session_id)::numeric / nullif(count(distinct es.session_id), 0), 4), 0)::numeric(12,4) as exposure_to_order_cr,
    coalesce(round(coalesce(sum(os.totale), 0) / nullif(count(distinct es.session_id), 0), 2), 0)::numeric(12,2) as revenue_per_exposed_session
from variants v
left join exposed_sessions es on es.variant = v.variant
left join ordered_sessions os
    on os.variant = v.variant
   and os.session_id = es.session_id
group by v.variant
order by v.variant;

-- ============================================================
-- QUERY 5: Optional current experiment mode card
-- ============================================================
select
    coalesce(
        (
            select
                case
                    when em.mode is null or btrim(em.mode) = '' then 'MODE_ABC'
                    when em.mode like 'MODE_%' then em.mode
                    else 'MODE_' || em.mode
                end
            from experiment_mode em
            where em.restaurant_id = {{ristorante_id}}
        ),
        'MODE_ABC'
    ) as current_mode;
