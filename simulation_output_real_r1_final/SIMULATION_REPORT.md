# WAITER0 User Simulation Report

## Scope

- Backend API reale: http://localhost:8080.
- Durata simulata: 30 giorni, dal 2026-04-01 al 2026-04-30.
- Ristorante reale disponibile nel DB: 1 - Spaghetti In Corso.
- Nota: il DB locale contiene un solo ristorante. Sono stati simulati 3 profili di traffico distinti sullo stesso tenant reale, non 3 tenant separati.
- A/B: variante assegnata esclusivamente dal backend tramite experiment_assignment, usando gli stessi sessionId passati alle API menu/upsell/ordine.
- Ordini: senza api_token, il simulatore non chiude automaticamente gli ordini aperti. Il report economico usa il valore incrementale del carrello della singola sessione.

## Overall

- Sessioni simulate: 550
- Sessioni con ordine: 160
- Conversion rate: 29.09%
- Revenue simulata incrementale: EUR 3651.70
- AOV: EUR 22.82
- Items per order: 2.23
- Upsell rate: 18.12%
- Views/session: 6.92
- Clicks/session: 2.91
- Error sessions: 168

## A/B Performance

- Variant A: sessions=150, orders=57, revenue=EUR 1349.40, AOV=EUR 23.67, items/order=2.35, upsell=19.30%, conversion=38.00%, errors=0
- Variant B: sessions=187, orders=83, revenue=EUR 1921.30, AOV=EUR 23.15, items/order=2.29, upsell=18.07%, conversion=44.39%, errors=0
- Variant HOLDOUT: sessions=45, orders=20, revenue=EUR 381.00, AOV=EUR 19.05, items/order=1.65, upsell=15.00%, conversion=44.44%, errors=0
- Variant UNKNOWN: sessions=168, orders=0, revenue=EUR 0.00, AOV=EUR 0.00, items/order=0.00, upsell=0.00%, conversion=0.00%, errors=168

- A vs B AOV uplift: EUR 0.53 (2.27%).
- A vs B revenue/session uplift: EUR -1.28 (-12.44%).
- Interpretazione: questo run e adatto come behavioral smoke test e prima lettura direzionale. Non e una prova statistica definitiva perche usa un solo tenant reale e i gruppi condividono lo stesso menu/storico.

## Traffic Profiles

- Spaghetti In Corso - dinner profile: sessions=209, orders=56, revenue=EUR 1170.80, AOV=EUR 20.91, upsell=19.64%, conversion=26.79%, errors=63
- Spaghetti In Corso - lunch profile: sessions=158, orders=41, revenue=EUR 1030.00, AOV=EUR 25.12, upsell=12.20%, conversion=25.95%, errors=43
- Spaghetti In Corso - weekend heavy profile: sessions=183, orders=63, revenue=EUR 1450.90, AOV=EUR 23.03, upsell=20.63%, conversion=34.43%, errors=62

## Personas

- CHAOTIC: sessions=48, orders=20, revenue=EUR 477.00, AOV=EUR 23.85, items/order=2.40, upsell=15.00%, conversion=41.67%
- HIGH_SPENDER: sessions=52, orders=38, revenue=EUR 1414.20, AOV=EUR 37.22, items/order=3.42, upsell=34.21%, conversion=73.08%
- LOW_SPENDER: sessions=100, orders=21, revenue=EUR 232.50, AOV=EUR 11.07, items/order=1.29, upsell=9.52%, conversion=21.00%
- STANDARD: sessions=182, orders=81, revenue=EUR 1528.00, AOV=EUR 18.86, items/order=1.88, upsell=13.58%, conversion=44.51%
- UNKNOWN: sessions=168, orders=0, revenue=EUR 0.00, AOV=EUR 0.00, items/order=0.00, upsell=0.00%, conversion=0.00%

## Output Files

- simulation_sessions.csv: session-level behavior and outcomes.
- simulation_daily_metrics.csv: daily metrics by traffic profile.
- simulation_ab_metrics.csv: analytics API output; unavailable without analytics token.
- simulation_variant_metrics_joined.csv: A/B metrics joined from backend assignment table and simulation sessions.

## Production Notes

- Per un test pienamente valido servono 3 ristoranti reali separati o cloni tenant con menu/tavoli/token propri.
- Per evitare ordini aggregati sullo stesso tavolo, configura api_token nel JSON: il simulatore paghera ogni ordine via API ristoratore dopo il submit cliente.
- La simulazione non favorisce la variante A: scelta piatti, click, carrello e upsell sono rumorosi e probabilistici; la variante viene letta dal backend solo a posteriori.
