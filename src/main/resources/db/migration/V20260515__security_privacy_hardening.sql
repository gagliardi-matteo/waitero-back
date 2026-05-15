CREATE INDEX IF NOT EXISTS idx_table_device_last_seen ON table_device(last_seen);
CREATE INDEX IF NOT EXISTS idx_table_access_log_timestamp ON table_access_log("timestamp");

ALTER TABLE stripe_webhook_event
    ADD COLUMN IF NOT EXISTS customer_id varchar(128);

ALTER TABLE stripe_webhook_event
    ADD COLUMN IF NOT EXISTS billing_review_id bigint;

ALTER TABLE stripe_webhook_event
    ADD COLUMN IF NOT EXISTS processing_status varchar(32);

ALTER TABLE stripe_webhook_event
    ADD COLUMN IF NOT EXISTS error_summary varchar(512);

UPDATE stripe_webhook_event
SET processing_status = 'PROCESSED'
WHERE processing_status IS NULL OR btrim(processing_status) = '';

ALTER TABLE stripe_webhook_event
    ALTER COLUMN processing_status SET DEFAULT 'PROCESSED';

ALTER TABLE stripe_webhook_event
    ALTER COLUMN processing_status SET NOT NULL;

ALTER TABLE stripe_webhook_event
    ALTER COLUMN payload DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_stripe_webhook_event_customer_id ON stripe_webhook_event(customer_id);
