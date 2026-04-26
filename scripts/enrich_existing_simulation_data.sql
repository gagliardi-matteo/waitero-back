BEGIN;

CREATE TEMP TABLE target_restaurants AS
SELECT DISTINCT o.ristoratore_id AS restaurant_id
FROM customer_orders o
UNION
SELECT DISTINCT el.restaurant_id
FROM event_log el
WHERE el.restaurant_id IS NOT NULL;

WITH submitted_events AS (
    SELECT DISTINCT ON (
        el.restaurant_id,
        ((el.metadata ->> 'orderId')::bigint)
    )
        el.restaurant_id,
        ((el.metadata ->> 'orderId')::bigint) AS order_id,
        NULLIF(BTRIM(el.session_id), '') AS session_id
    FROM event_log el
    JOIN target_restaurants tr ON tr.restaurant_id = el.restaurant_id
    WHERE el.event_type = 'order_submitted'
      AND el.metadata ? 'orderId'
      AND NULLIF(BTRIM(el.session_id), '') IS NOT NULL
    ORDER BY el.restaurant_id, ((el.metadata ->> 'orderId')::bigint), el.created_at DESC
)
UPDATE customer_orders o
SET session_id = se.session_id
FROM submitted_events se
WHERE o.id = se.order_id
  AND o.ristoratore_id = se.restaurant_id
  AND (o.session_id IS NULL OR BTRIM(o.session_id) = '');

UPDATE customer_orders o
SET session_id = SUBSTRING(o.note_cucina FROM 'session=([^ ]+)')
WHERE (o.session_id IS NULL OR BTRIM(o.session_id) = '')
  AND o.note_cucina IS NOT NULL
  AND o.note_cucina LIKE '%session=%';

UPDATE customer_orders o
SET session_id = FORMAT('retro-%s-%s', o.ristoratore_id, o.id)
WHERE (o.session_id IS NULL OR BTRIM(o.session_id) = '')
  AND EXISTS (
      SELECT 1
      FROM target_restaurants tr
      WHERE tr.restaurant_id = o.ristoratore_id
  );

WITH item_totals AS (
    SELECT
        oi.ordine_id,
        COALESCE(SUM(oi.quantity), 0) AS item_count
    FROM customer_order_items oi
    GROUP BY oi.ordine_id
)
UPDATE customer_orders o
SET item_count = it.item_count
FROM item_totals it
WHERE o.id = it.ordine_id
  AND COALESCE(o.item_count, -1) <> it.item_count;

WITH interactions AS (
    SELECT
        el.restaurant_id,
        NULLIF(BTRIM(el.session_id), '') AS session_id,
        el.table_id,
        el.user_id,
        el.dish_id,
        MIN(el.created_at) AS first_seen_at
    FROM event_log el
    JOIN target_restaurants tr ON tr.restaurant_id = el.restaurant_id
    WHERE NULLIF(BTRIM(el.session_id), '') IS NOT NULL
      AND el.dish_id IS NOT NULL
      AND el.event_type IN ('view_dish', 'click_dish', 'add_to_cart')
    GROUP BY el.restaurant_id, NULLIF(BTRIM(el.session_id), ''), el.table_id, el.user_id, el.dish_id
),
missing_impressions AS (
    SELECT i.*
    FROM interactions i
    WHERE NOT EXISTS (
        SELECT 1
        FROM event_log existing
        WHERE existing.restaurant_id = i.restaurant_id
          AND NULLIF(BTRIM(existing.session_id), '') = i.session_id
          AND existing.event_type = 'view_menu_item'
          AND existing.dish_id = i.dish_id
    )
)
INSERT INTO event_log (
    id,
    created_at,
    dish_id,
    event_type,
    metadata,
    restaurant_id,
    session_id,
    table_id,
    user_id
)
SELECT
    (
        SUBSTR(MD5(CONCAT(mi.restaurant_id, ':', mi.session_id, ':', mi.dish_id, ':view_menu_item')), 1, 8) || '-' ||
        SUBSTR(MD5(CONCAT(mi.restaurant_id, ':', mi.session_id, ':', mi.dish_id, ':view_menu_item')), 9, 4) || '-' ||
        SUBSTR(MD5(CONCAT(mi.restaurant_id, ':', mi.session_id, ':', mi.dish_id, ':view_menu_item')), 13, 4) || '-' ||
        SUBSTR(MD5(CONCAT(mi.restaurant_id, ':', mi.session_id, ':', mi.dish_id, ':view_menu_item')), 17, 4) || '-' ||
        SUBSTR(MD5(CONCAT(mi.restaurant_id, ':', mi.session_id, ':', mi.dish_id, ':view_menu_item')), 21, 12)
    )::uuid,
    mi.first_seen_at - INTERVAL '45 seconds',
    mi.dish_id,
    'view_menu_item',
    jsonb_build_object('seed', 'simulation-backfill', 'source', 'retro-impression'),
    mi.restaurant_id,
    mi.session_id,
    mi.table_id,
    mi.user_id
FROM missing_impressions mi;

CREATE TEMP TABLE session_assignments AS
WITH all_sessions AS (
    SELECT
        o.ristoratore_id AS restaurant_id,
        NULLIF(BTRIM(o.session_id), '') AS session_id,
        MIN(o.created_at) AS created_at
    FROM customer_orders o
    JOIN target_restaurants tr ON tr.restaurant_id = o.ristoratore_id
    WHERE NULLIF(BTRIM(o.session_id), '') IS NOT NULL
    GROUP BY o.ristoratore_id, NULLIF(BTRIM(o.session_id), '')

    UNION ALL

    SELECT
        el.restaurant_id,
        NULLIF(BTRIM(el.session_id), '') AS session_id,
        MIN(el.created_at) AS created_at
    FROM event_log el
    JOIN target_restaurants tr ON tr.restaurant_id = el.restaurant_id
    WHERE NULLIF(BTRIM(el.session_id), '') IS NOT NULL
    GROUP BY el.restaurant_id, NULLIF(BTRIM(el.session_id), '')
),
collapsed AS (
    SELECT
        restaurant_id,
        session_id,
        MIN(created_at) AS created_at
    FROM all_sessions
    GROUP BY restaurant_id, session_id
)
SELECT
    c.restaurant_id,
    c.session_id,
    c.created_at,
    CASE
        WHEN MOD(ABS(HASHTEXT(c.session_id || ':' || c.restaurant_id::text))::bigint, 100) < 34 THEN 'A'
        WHEN MOD(ABS(HASHTEXT(c.session_id || ':' || c.restaurant_id::text))::bigint, 100) < 67 THEN 'B'
        ELSE 'C'
    END AS variant
FROM collapsed c;

INSERT INTO experiment_assignment (
    restaurant_id,
    session_id,
    created_at,
    variant
)
SELECT
    sa.restaurant_id,
    sa.session_id,
    sa.created_at,
    sa.variant
FROM session_assignments sa
ON CONFLICT (restaurant_id, session_id) DO UPDATE
SET created_at = LEAST(experiment_assignment.created_at, EXCLUDED.created_at),
    variant = EXCLUDED.variant;

UPDATE customer_orders o
SET variant = sa.variant
FROM session_assignments sa
WHERE o.ristoratore_id = sa.restaurant_id
  AND NULLIF(BTRIM(o.session_id), '') = sa.session_id
  AND o.variant IS DISTINCT FROM sa.variant;

INSERT INTO experiment_mode (restaurant_id, mode)
SELECT
    tr.restaurant_id,
    'ABC'
FROM target_restaurants tr
ON CONFLICT (restaurant_id) DO UPDATE
SET mode = EXCLUDED.mode;

INSERT INTO experiment_config (
    restaurant_id,
    autopilot_enabled,
    min_sample_size,
    min_uplift_percent,
    min_confidence,
    holdout_percent,
    updated_at
)
SELECT
    tr.restaurant_id,
    TRUE,
    50,
    5.0,
    0.95,
    5,
    CURRENT_TIMESTAMP
FROM target_restaurants tr
ON CONFLICT (restaurant_id) DO UPDATE
SET autopilot_enabled = TRUE,
    min_sample_size = EXCLUDED.min_sample_size,
    min_uplift_percent = EXCLUDED.min_uplift_percent,
    min_confidence = EXCLUDED.min_confidence,
    holdout_percent = EXCLUDED.holdout_percent,
    updated_at = CURRENT_TIMESTAMP;

COMMIT;
