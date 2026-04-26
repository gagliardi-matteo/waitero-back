# WaiterO Experiment A/B/C Dashboard

This dashboard is meant for Metabase or a similar BI tool and is aligned with the current WaiterO backend.

## Scope

- Variant `A` = legacy ranking
- Variant `B` = analyticsv2 ranking
- Variant `C` = DishScore ranking

The authoritative query for autopilot comparison is the core metrics query built on `customer_orders`. That matches the backend `ExperimentAnalyticsService` logic.

## Source files

- SQL queries: [scripts/experiment_abc_dashboard.sql](/E:/Sviluppo/waitero-back/scripts/experiment_abc_dashboard.sql)
- Backend metric logic: [ExperimentAnalyticsService.java](/E:/Sviluppo/waitero-back/src/main/java/com/waitero/back/service/ExperimentAnalyticsService.java:1)
- Winner logic: [ExperimentDecisionService.java](/E:/Sviluppo/waitero-back/src/main/java/com/waitero/back/service/ExperimentDecisionService.java:1)

## Global filters

Use these dashboard-level filters and map them into every card:

- `ristorante_id` as number
- `start_date` as date
- `end_date` as date

Recommended default window:

- last 30 days

## Recommended questions

### 1. KPI cards

Base them on Query 1 and either:

- show one table with `A`, `B`, `C`
- or create three separate cards filtered per variant

Metrics to expose:

- `RPS`
- `AOV`
- `CR`
- optionally `total_revenue`, `total_orders`, `total_sessions`

### 2. Bar chart

Use Query 1.

Configuration:

- X axis: `variant`
- Y axis: `rps`
- sort: `A`, `B`, `C`

### 3. Line chart

Use Query 2.

Configuration:

- X axis: `day`
- Y axis: `rps`
- series breakout: `variant`

This is the quickest way to spot drift, instability, or sudden reversals between variants.

### 4. Top dishes table

Use Query 3.

Recommended columns:

- `variant`
- `rank_in_variant`
- `dish_name`
- `categoria`
- `total_orders`
- `units_sold`
- `total_revenue`

This helps explain why a variant is winning. If `C` wins on RPS, this table shows whether the uplift comes from better dish mix, more premium dishes, or stronger upsell combinations.

### 5. Optional funnel card

Use Query 4.

This is not the source of truth for autopilot. It is diagnostic. Use it when you want to understand:

- how many assigned sessions actually saw menu items
- how many exposed sessions turned into ordering sessions
- whether revenue gains come from better conversion or higher ticket size

### 6. Optional current mode card

Use Query 5.

This lets the dashboard show whether the restaurant is currently in:

- `MODE_ABC`
- `MODE_FORCE_A`
- `MODE_FORCE_B`
- `MODE_FORCE_C`

## Interpretation

- If `C.rps > B.rps` by more than 5%, DishScore is winning on revenue efficiency.
- If `AOV` increases while `CR` drops, the ranking is likely promoting more expensive dishes but hurting conversion.
- If `CR` increases while `AOV` drops, the ranking is converting more sessions but with weaker average ticket.
- If `RPS` is flat but the line chart is volatile, do not force a switch yet. That usually means unstable traffic or uneven daily mix.

## Consistency notes

- Variants are normalized to uppercase `A`, `B`, `C`.
- Blank `session_id` values are ignored for session counts.
- Query 1 returns all three variants even when one has zero data.
- All ratios use `NULLIF` and `COALESCE` to avoid division-by-zero and `NaN`.

## Performance notes

The schema already has these useful indexes:

- `customer_orders(ristoratore_id, created_at, variant, session_id)`
- `customer_orders(ristoratore_id, variant)`
- `event_log(restaurant_id, created_at)`
- `event_log(session_id)`
- `experiment_assignment(restaurant_id, variant)`

An additional index has been added for the top-dishes query path:

- `customer_order_items(ordine_id, piatto_id)`

## Recommendation

For product validation and autopilot review:

- make Query 1 the main scorecard
- keep Query 2 directly below it
- add Query 3 as the explanatory table
- keep Query 4 and Query 5 on the side as operational diagnostics
