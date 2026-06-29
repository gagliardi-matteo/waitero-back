ALTER TABLE table_device
  ADD COLUMN IF NOT EXISTS location_unverified boolean NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_table_device_location_unverified
  ON table_device(table_id, device_id, location_unverified)
  WHERE location_unverified = true;
