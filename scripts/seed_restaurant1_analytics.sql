BEGIN;

CREATE TEMP TABLE seed_dish_source (
  nome text,
  categoria text,
  prezzo numeric(10,2),
  descrizione text,
  ingredienti text,
  allergeni text,
  consigliato boolean,
  disponibile boolean
) ON COMMIT DROP;

INSERT INTO seed_dish_source (nome, categoria, prezzo, descrizione, ingredienti, allergeni, consigliato, disponibile) VALUES
('Bruschette del giorno', 'ANTIPASTO', 8.50, 'Pane caldo, pomodorini confit e basilico', 'pane, pomodorini, basilico, olio evo', 'GLUTINE', true, true),
('Suppli cacio e pepe', 'ANTIPASTO', 7.00, 'Croccante fuori, cremoso dentro', 'riso, pecorino, pepe, pangrattato', 'GLUTINE,LATTE', false, true),
('Cacio e pepe', 'PRIMO', 13.50, 'Tradizione romana mantecata al momento', 'spaghetti, pecorino, pepe nero', 'GLUTINE,LATTE', true, true),
('Gricia croccante', 'PRIMO', 14.00, 'Guanciale tostato e pecorino romano', 'rigatoni, guanciale, pecorino, pepe', 'GLUTINE,LATTE', false, true),
('Lasagna bianca ai carciofi', 'PRIMO', 14.50, 'Strati cremosi con carciofi e fonduta leggera', 'sfoglia, carciofi, besciamella, parmigiano', 'GLUTINE,LATTE,UOVA', false, true),
('Saltimbocca alla romana', 'SECONDO', 18.00, 'Vitello, prosciutto crudo e salvia', 'vitello, prosciutto crudo, salvia', '', true, true),
('Polpette al sugo', 'SECONDO', 16.00, 'Polpette morbide con salsa di pomodoro', 'manzo, pomodoro, parmigiano, pane', 'GLUTINE,LATTE,UOVA', false, true),
('Tagliata rucola e grana', 'SECONDO', 21.00, 'Controfiletto con rucola e scaglie', 'manzo, rucola, grana, olio evo', 'LATTE', true, true),
('Patate al forno', 'CONTORNO', 6.00, 'Patate dorate con rosmarino', 'patate, rosmarino, olio evo', '', false, true),
('Cicoria ripassata', 'CONTORNO', 6.50, 'Verdura saltata con aglio e peperoncino', 'cicoria, aglio, peperoncino', '', false, true),
('Cheesecake al pistacchio', 'DOLCE', 7.50, 'Cremosa e intensa, con granella finale', 'biscotti, burro, formaggio, pistacchio', 'GLUTINE,LATTE,FRUTTA_A_GUSCIO', false, true),
('Cannolo espresso', 'DOLCE', 6.50, 'Cialda croccante e ricotta montata', 'cialda, ricotta, zucchero, arancia', 'GLUTINE,LATTE', false, true),
('Calice rosso della casa', 'BEVANDA', 5.50, 'Rosso morbido in abbinamento ai secondi', 'vino rosso', 'SOLFITI', false, true),
('Birra artigianale 33cl', 'BEVANDA', 6.00, 'Chiara non filtrata dal finale secco', 'birra', 'GLUTINE', false, true),
('Acqua frizzante', 'BEVANDA', 2.50, 'Bottiglia da 75cl', 'acqua minerale', '', false, true),
('Caffe espresso', 'BEVANDA', 1.50, 'Miscela intensa servita al banco', 'caffe', '', false, true);

CREATE TEMP TABLE seeded_orders AS
SELECT id
FROM customer_orders
WHERE ristoratore_id = 1
  AND note_cucina LIKE 'LT_ANALYTICS_R1%';

DELETE FROM customer_order_payment_allocations
WHERE payment_id IN (
  SELECT id FROM customer_order_payments WHERE ordine_id IN (SELECT id FROM seeded_orders)
);

DELETE FROM customer_order_payments
WHERE ordine_id IN (SELECT id FROM seeded_orders);

DELETE FROM customer_order_items
WHERE ordine_id IN (SELECT id FROM seeded_orders);

DELETE FROM customer_orders
WHERE id IN (SELECT id FROM seeded_orders);

DELETE FROM event_log
WHERE restaurant_id = 1
  AND metadata ->> 'seed' = 'LT_ANALYTICS_R1';

DELETE FROM table_access_log
WHERE reason = 'LT_ANALYTICS_R1'
  AND table_id IN (SELECT id FROM tavoli WHERE ristoratore_id = 1);

DELETE FROM table_device
WHERE device_id LIKE 'lt-r1-%'
  AND table_id IN (SELECT id FROM tavoli WHERE ristoratore_id = 1);

DELETE FROM dish_cooccurrence
WHERE base_dish_id IN (SELECT id FROM piatto WHERE ristoratore_id = 1)
   OR suggested_dish_id IN (SELECT id FROM piatto WHERE ristoratore_id = 1);

DELETE FROM piatto
WHERE ristoratore_id = 1
  AND nome IN (SELECT nome FROM seed_dish_source);

INSERT INTO piatto (nome, categoria, prezzo, descrizione, ingredienti, allergeni, consigliato, disponibile, ristoratore_id, image_url)
SELECT nome, categoria, prezzo, descrizione, ingredienti, allergeni, consigliato, disponibile, 1, NULL
FROM seed_dish_source;

DO $$
DECLARE
  session_day date;
  session_counter integer;
  sessions_for_day integer;
  chosen_table_id bigint;
  access_ts timestamp;
  session_id text;
  user_id text;
  device_id text;
  fingerprint text;
  viewed_count integer;
  dish_rec record;
  order_probability double precision;
  should_order boolean;
  order_created_at timestamp;
  order_id bigint;
  order_status text;
  v_payment_mode text;
  order_total numeric(10,2);
  items_to_insert integer;
  selected_dish_id bigint;
  selected_name text;
  selected_price numeric(10,2);
  selected_image text;
  qty integer;
  item_id bigint;
  payment_id bigint;
  item_rec record;
  partial_target numeric(10,2);
  partial_paid numeric(10,2);
  alloc_qty integer;
  paid_amount numeric(10,2);
BEGIN
  FOR session_day IN SELECT generate_series(current_date - 20, current_date, interval '1 day')::date LOOP
    sessions_for_day := 9 + floor(random() * 6)::integer;

    FOR session_counter IN 1..sessions_for_day LOOP
      SELECT id
      INTO chosen_table_id
      FROM tavoli
      WHERE ristoratore_id = 1 AND attivo = true
      ORDER BY random()
      LIMIT 1;

      access_ts := session_day::timestamp
        + interval '11 hours'
        + make_interval(mins => (floor(random() * 720))::integer);

      session_id := format('lt-r1-%s-%s', to_char(session_day, 'YYYYMMDD'), lpad(session_counter::text, 2, '0'));
      user_id := format('guest-r1-%s', substr(md5(session_id), 1, 10));
      device_id := format('lt-r1-device-%s', substr(md5(session_id || '-device'), 1, 12));
      fingerprint := format('fp-%s', substr(md5(session_id || '-fingerprint'), 1, 16));

      INSERT INTO table_device (device_id, fingerprint, first_seen, last_seen, table_id)
      VALUES (device_id, fingerprint, access_ts, access_ts + interval '35 minutes', chosen_table_id);

      INSERT INTO table_access_log (accuracy, device_id, fingerprint, latitude, longitude, reason, risk_score, timestamp, table_id)
      VALUES (
        18 + random() * 22,
        device_id,
        fingerprint,
        41.9028 + ((random() - 0.5) / 300),
        12.4964 + ((random() - 0.5) / 300),
        'LT_ANALYTICS_R1',
        2 + floor(random() * 6)::integer,
        access_ts,
        chosen_table_id
      );

      INSERT INTO event_log (id, created_at, dish_id, event_type, metadata, restaurant_id, session_id, table_id, user_id)
      VALUES (
        (substr(md5(session_id || '-scroll'), 1, 8) || '-' || substr(md5(session_id || '-scroll'), 9, 4) || '-' || substr(md5(session_id || '-scroll'), 13, 4) || '-' || substr(md5(session_id || '-scroll'), 17, 4) || '-' || substr(md5(session_id || '-scroll'), 21, 12))::uuid,
        access_ts + interval '2 minutes',
        NULL,
        'scroll',
        jsonb_build_object('seed', 'LT_ANALYTICS_R1', 'depth', 35 + floor(random() * 65)::integer),
        1,
        session_id,
        chosen_table_id,
        user_id
      );

      INSERT INTO event_log (id, created_at, dish_id, event_type, metadata, restaurant_id, session_id, table_id, user_id)
      VALUES (
        (substr(md5(session_id || '-time'), 1, 8) || '-' || substr(md5(session_id || '-time'), 9, 4) || '-' || substr(md5(session_id || '-time'), 13, 4) || '-' || substr(md5(session_id || '-time'), 17, 4) || '-' || substr(md5(session_id || '-time'), 21, 12))::uuid,
        access_ts + interval '28 minutes',
        NULL,
        'time_spent',
        jsonb_build_object('seed', 'LT_ANALYTICS_R1', 'seconds', 180 + floor(random() * 780)::integer),
        1,
        session_id,
        chosen_table_id,
        user_id
      );

      viewed_count := 4 + floor(random() * 5)::integer;

      FOR dish_rec IN
        SELECT id, nome, categoria
        FROM piatto
        WHERE ristoratore_id = 1
        ORDER BY
          CASE
            WHEN nome IN ('Carbonara', 'Cacio e pepe', 'Fiorentina', 'Tiramisu', 'Tagliata rucola e grana') THEN 0
            WHEN categoria = 'BEVANDA' THEN 2
            ELSE 1
          END,
          random()
        LIMIT viewed_count
      LOOP
        INSERT INTO event_log (id, created_at, dish_id, event_type, metadata, restaurant_id, session_id, table_id, user_id)
        VALUES (
          (substr(md5(session_id || '-view-' || dish_rec.id), 1, 8) || '-' || substr(md5(session_id || '-view-' || dish_rec.id), 9, 4) || '-' || substr(md5(session_id || '-view-' || dish_rec.id), 13, 4) || '-' || substr(md5(session_id || '-view-' || dish_rec.id), 17, 4) || '-' || substr(md5(session_id || '-view-' || dish_rec.id), 21, 12))::uuid,
          access_ts + make_interval(mins => 3 + floor(random() * 18)::integer),
          dish_rec.id,
          'view_dish',
          jsonb_build_object('seed', 'LT_ANALYTICS_R1', 'dishName', dish_rec.nome),
          1,
          session_id,
          chosen_table_id,
          user_id
        );

        IF random() < (CASE WHEN dish_rec.nome IN ('Fiorentina', 'Carbonara', 'Cacio e pepe', 'Tiramisu') THEN 0.88 ELSE 0.67 END) THEN
          INSERT INTO event_log (id, created_at, dish_id, event_type, metadata, restaurant_id, session_id, table_id, user_id)
          VALUES (
            (substr(md5(session_id || '-click-' || dish_rec.id), 1, 8) || '-' || substr(md5(session_id || '-click-' || dish_rec.id), 9, 4) || '-' || substr(md5(session_id || '-click-' || dish_rec.id), 13, 4) || '-' || substr(md5(session_id || '-click-' || dish_rec.id), 17, 4) || '-' || substr(md5(session_id || '-click-' || dish_rec.id), 21, 12))::uuid,
            access_ts + make_interval(mins => 4 + floor(random() * 20)::integer),
            dish_rec.id,
            'click_dish',
            jsonb_build_object('seed', 'LT_ANALYTICS_R1', 'dishName', dish_rec.nome),
            1,
            session_id,
            chosen_table_id,
            user_id
          );
        END IF;

        IF random() < (CASE
          WHEN dish_rec.categoria = 'BEVANDA' THEN 0.22
          WHEN dish_rec.nome IN ('Carbonara', 'Cacio e pepe', 'Tiramisu', 'Tagliata rucola e grana') THEN 0.54
          WHEN dish_rec.nome = 'Fiorentina' THEN 0.31
          ELSE 0.36
        END) THEN
          INSERT INTO event_log (id, created_at, dish_id, event_type, metadata, restaurant_id, session_id, table_id, user_id)
          VALUES (
            (substr(md5(session_id || '-add-' || dish_rec.id), 1, 8) || '-' || substr(md5(session_id || '-add-' || dish_rec.id), 9, 4) || '-' || substr(md5(session_id || '-add-' || dish_rec.id), 13, 4) || '-' || substr(md5(session_id || '-add-' || dish_rec.id), 17, 4) || '-' || substr(md5(session_id || '-add-' || dish_rec.id), 21, 12))::uuid,
            access_ts + make_interval(mins => 6 + floor(random() * 22)::integer),
            dish_rec.id,
            'add_to_cart',
            jsonb_build_object('seed', 'LT_ANALYTICS_R1', 'dishName', dish_rec.nome),
            1,
            session_id,
            chosen_table_id,
            user_id
          );

          IF random() < 0.18 THEN
            INSERT INTO event_log (id, created_at, dish_id, event_type, metadata, restaurant_id, session_id, table_id, user_id)
            VALUES (
              (substr(md5(session_id || '-remove-' || dish_rec.id), 1, 8) || '-' || substr(md5(session_id || '-remove-' || dish_rec.id), 9, 4) || '-' || substr(md5(session_id || '-remove-' || dish_rec.id), 13, 4) || '-' || substr(md5(session_id || '-remove-' || dish_rec.id), 17, 4) || '-' || substr(md5(session_id || '-remove-' || dish_rec.id), 21, 12))::uuid,
              access_ts + make_interval(mins => 9 + floor(random() * 24)::integer),
              dish_rec.id,
              'remove_from_cart',
              jsonb_build_object('seed', 'LT_ANALYTICS_R1', 'dishName', dish_rec.nome),
              1,
              session_id,
              chosen_table_id,
              user_id
            );
          END IF;
        END IF;
      END LOOP;

      order_probability := CASE
        WHEN extract(dow from session_day) IN (5, 6) THEN 0.78
        ELSE 0.62
      END;
      should_order := random() < order_probability;

      IF should_order THEN
        order_created_at := access_ts + make_interval(mins => 12 + floor(random() * 26)::integer);
        order_status := CASE
          WHEN random() < 0.74 THEN 'PAGATO'
          WHEN random() < 0.88 THEN 'PARZIALMENTE_PAGATO'
          WHEN random() < 0.97 THEN 'APERTO'
          ELSE 'ANNULLATO'
        END;

        v_payment_mode := CASE
          WHEN random() < 0.52 THEN 'CARTA'
          WHEN random() < 0.82 THEN 'CONTANTI'
          ELSE 'POS_AL_TAVOLO'
        END;

        INSERT INTO customer_orders (created_at, paid_at, payment_mode, status, table_id, updated_at, ristoratore_id, note_cucina, totale)
        VALUES (
          order_created_at,
          CASE WHEN order_status = 'PAGATO' THEN order_created_at + interval '35 minutes' ELSE NULL END,
          CASE WHEN order_status IN ('PAGATO', 'PARZIALMENTE_PAGATO') THEN v_payment_mode ELSE NULL END,
          order_status,
          chosen_table_id::integer,
          order_created_at + interval '40 minutes',
          1,
          format('LT_ANALYTICS_R1 session=%s day=%s', session_id, to_char(session_day, 'YYYY-MM-DD')),
          0
        )
        RETURNING id INTO order_id;

        order_total := 0;
        items_to_insert := 1 + floor(random() * 4)::integer;

        FOR session_counter IN 1..items_to_insert LOOP
          SELECT id, nome, prezzo, image_url
          INTO selected_dish_id, selected_name, selected_price, selected_image
          FROM piatto
          WHERE ristoratore_id = 1
          ORDER BY
            CASE
              WHEN nome IN ('Carbonara', 'Cacio e pepe', 'Tiramisu', 'Tagliata rucola e grana') THEN 0
              WHEN categoria = 'BEVANDA' THEN 2
              WHEN nome = 'Fiorentina' THEN 1
              ELSE 1
            END,
            random()
          LIMIT 1;

          qty := CASE
            WHEN selected_name IN ('Acqua frizzante', 'Calice rosso della casa', 'Birra artigianale 33cl', 'Caffe espresso') THEN 1 + floor(random() * 2)::integer
            WHEN selected_name IN ('Carbonara', 'Cacio e pepe', 'Amatriciana') AND random() < 0.25 THEN 2
            ELSE 1
          END;

          INSERT INTO customer_order_items (created_at, image_url, nome, prezzo_unitario, quantity, ordine_id, piatto_id)
          VALUES (order_created_at, selected_image, selected_name, selected_price, qty, order_id, selected_dish_id)
          RETURNING id INTO item_id;

          order_total := order_total + (selected_price * qty);
        END LOOP;

        UPDATE customer_orders
        SET totale = order_total,
            paid_at = CASE WHEN order_status = 'PAGATO' THEN order_created_at + interval '35 minutes' ELSE paid_at END,
            payment_mode = CASE WHEN order_status IN ('PAGATO', 'PARZIALMENTE_PAGATO') THEN v_payment_mode ELSE NULL END
        WHERE id = order_id;

        INSERT INTO event_log (id, created_at, dish_id, event_type, metadata, restaurant_id, session_id, table_id, user_id)
        VALUES (
          (substr(md5(session_id || '-submit-' || order_id), 1, 8) || '-' || substr(md5(session_id || '-submit-' || order_id), 9, 4) || '-' || substr(md5(session_id || '-submit-' || order_id), 13, 4) || '-' || substr(md5(session_id || '-submit-' || order_id), 17, 4) || '-' || substr(md5(session_id || '-submit-' || order_id), 21, 12))::uuid,
          order_created_at,
          NULL,
          'order_submitted',
          jsonb_build_object('seed', 'LT_ANALYTICS_R1', 'orderId', order_id, 'total', order_total),
          1,
          session_id,
          chosen_table_id,
          user_id
        );

        IF order_status = 'PAGATO' THEN
          INSERT INTO customer_order_payments (amount, created_at, participant_name, payment_mode, ordine_id)
          VALUES (order_total, order_created_at + interval '35 minutes', 'Tavolo completo', v_payment_mode, order_id)
          RETURNING id INTO payment_id;

          FOR item_rec IN
            SELECT id, quantity, prezzo_unitario
            FROM customer_order_items
            WHERE ordine_id = order_id
          LOOP
            INSERT INTO customer_order_payment_allocations (quantity, unit_price, order_item_id, payment_id)
            VALUES (item_rec.quantity, item_rec.prezzo_unitario, item_rec.id, payment_id);
          END LOOP;
        ELSIF order_status = 'PARZIALMENTE_PAGATO' THEN
          partial_target := round((order_total * (0.45 + random() * 0.25))::numeric, 2);
          partial_paid := 0;

          INSERT INTO customer_order_payments (amount, created_at, participant_name, payment_mode, ordine_id)
          VALUES (0, order_created_at + interval '28 minutes', 'Acconto tavolo', v_payment_mode, order_id)
          RETURNING id INTO payment_id;

          FOR item_rec IN
            SELECT id, quantity, prezzo_unitario
            FROM customer_order_items
            WHERE ordine_id = order_id
            ORDER BY prezzo_unitario DESC, id
          LOOP
            EXIT WHEN partial_paid >= partial_target;

            alloc_qty := GREATEST(1, LEAST(item_rec.quantity, floor((partial_target - partial_paid) / item_rec.prezzo_unitario)::integer));
            IF alloc_qty <= 0 THEN
              alloc_qty := 1;
            END IF;

            paid_amount := alloc_qty * item_rec.prezzo_unitario;
            IF partial_paid + paid_amount > order_total THEN
              CONTINUE;
            END IF;

            INSERT INTO customer_order_payment_allocations (quantity, unit_price, order_item_id, payment_id)
            VALUES (alloc_qty, item_rec.prezzo_unitario, item_rec.id, payment_id);

            partial_paid := partial_paid + paid_amount;
          END LOOP;

          UPDATE customer_order_payments
          SET amount = partial_paid
          WHERE id = payment_id;
        END IF;
      END IF;
    END LOOP;
  END LOOP;
END $$;

INSERT INTO dish_cooccurrence (base_dish_id, suggested_dish_id, count, confidence)
WITH order_dishes AS (
  SELECT DISTINCT coi.ordine_id, coi.piatto_id
  FROM customer_order_items coi
  JOIN customer_orders co ON co.id = coi.ordine_id
  WHERE co.ristoratore_id = 1
    AND co.status <> 'ANNULLATO'
),
base_counts AS (
  SELECT piatto_id, COUNT(*) AS base_count
  FROM order_dishes
  GROUP BY piatto_id
),
pairs AS (
  SELECT od1.piatto_id AS base_dish_id, od2.piatto_id AS suggested_dish_id, COUNT(*) AS pair_count
  FROM order_dishes od1
  JOIN order_dishes od2
    ON od1.ordine_id = od2.ordine_id
   AND od1.piatto_id <> od2.piatto_id
  GROUP BY od1.piatto_id, od2.piatto_id
)
SELECT pairs.base_dish_id,
       pairs.suggested_dish_id,
       pairs.pair_count,
       ROUND((pairs.pair_count::numeric / GREATEST(base_counts.base_count, 1)), 4)::double precision
FROM pairs
JOIN base_counts ON base_counts.piatto_id = pairs.base_dish_id;

COMMIT;







