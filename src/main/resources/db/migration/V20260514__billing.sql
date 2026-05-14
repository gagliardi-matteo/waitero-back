CREATE TABLE IF NOT EXISTS billing_account (
    id BIGSERIAL PRIMARY KEY,
    ristoratore_id BIGINT NOT NULL,
    stripe_customer_id varchar(128),
    default_payment_method_id varchar(128),
    billing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    commission_percentage numeric(8,6) NOT NULL DEFAULT 0.010000,
    minimum_monthly_fee numeric(12,2) NOT NULL DEFAULT 0,
    billing_day integer NOT NULL,
    contract_start_date date NOT NULL,
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_billing_account_restaurant_unique
    ON billing_account(ristoratore_id);
CREATE INDEX IF NOT EXISTS idx_billing_account_enabled_day
    ON billing_account(billing_enabled, billing_day);

ALTER TABLE billing_account
    DROP CONSTRAINT IF EXISTS fk_billing_account_restaurant;
ALTER TABLE billing_account
    ADD CONSTRAINT fk_billing_account_restaurant
    FOREIGN KEY (ristoratore_id) REFERENCES ristoratore(id);

CREATE TABLE IF NOT EXISTS billing_review (
    id BIGSERIAL PRIMARY KEY,
    ristoratore_id BIGINT NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    gross_revenue_snapshot numeric(14,2) NOT NULL,
    order_count_snapshot integer NOT NULL,
    commission_percentage_snapshot numeric(8,6) NOT NULL,
    minimum_monthly_fee_snapshot numeric(12,2) NOT NULL,
    calculated_fee_snapshot numeric(14,2) NOT NULL,
    status varchar(24) NOT NULL,
    stripe_invoice_id varchar(128),
    approved_by BIGINT,
    approved_at timestamp(6) without time zone,
    notes varchar(2000),
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_billing_review_period_unique
    ON billing_review(ristoratore_id, period_start, period_end);
CREATE INDEX IF NOT EXISTS idx_billing_review_status_created
    ON billing_review(status, created_at);
CREATE INDEX IF NOT EXISTS idx_billing_review_invoice_id
    ON billing_review(stripe_invoice_id);
CREATE INDEX IF NOT EXISTS idx_billing_review_restaurant_period_end
    ON billing_review(ristoratore_id, period_end DESC);

ALTER TABLE billing_review
    DROP CONSTRAINT IF EXISTS fk_billing_review_restaurant;
ALTER TABLE billing_review
    ADD CONSTRAINT fk_billing_review_restaurant
    FOREIGN KEY (ristoratore_id) REFERENCES ristoratore(id);

CREATE TABLE IF NOT EXISTS billing_review_order_snapshot (
    id BIGSERIAL PRIMARY KEY,
    billing_review_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    order_total numeric(14,2) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_billing_review_order_snapshot_review
    ON billing_review_order_snapshot(billing_review_id);
CREATE INDEX IF NOT EXISTS idx_billing_review_order_snapshot_order
    ON billing_review_order_snapshot(order_id);

ALTER TABLE billing_review_order_snapshot
    DROP CONSTRAINT IF EXISTS fk_billing_review_order_snapshot_review;
ALTER TABLE billing_review_order_snapshot
    ADD CONSTRAINT fk_billing_review_order_snapshot_review
    FOREIGN KEY (billing_review_id) REFERENCES billing_review(id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS stripe_webhook_event (
    event_id varchar(128) PRIMARY KEY,
    event_type varchar(96) NOT NULL,
    invoice_id varchar(128),
    payload text NOT NULL,
    processed_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_stripe_webhook_event_invoice_id
    ON stripe_webhook_event(invoice_id);
CREATE INDEX IF NOT EXISTS idx_stripe_webhook_event_processed_at
    ON stripe_webhook_event(processed_at DESC);

CREATE TABLE IF NOT EXISTS billing_global_config (
    id BIGINT PRIMARY KEY,
    commission_percentage numeric(8,6) NOT NULL,
    minimum_monthly_fee numeric(12,2) NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO billing_global_config (id, commission_percentage, minimum_monthly_fee, updated_at)
VALUES (1, 0.010000, 0.00, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;
