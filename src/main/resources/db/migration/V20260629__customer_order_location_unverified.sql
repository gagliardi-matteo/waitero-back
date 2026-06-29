ALTER TABLE customer_orders
  ADD COLUMN IF NOT EXISTS location_unverified boolean NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_customer_orders_location_unverified
  ON customer_orders(ristoratore_id, location_unverified)
  WHERE location_unverified = true;
