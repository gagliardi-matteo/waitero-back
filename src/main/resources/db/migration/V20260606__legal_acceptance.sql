CREATE TABLE IF NOT EXISTS legal_acceptance (
    id BIGSERIAL PRIMARY KEY,
    type varchar(32) NOT NULL,
    restaurant_id bigint,
    table_public_id varchar(64),
    table_number integer,
    qr_token_hash varchar(128),
    session_id varchar(128),
    contract_version varchar(32),
    privacy_version varchar(32) NOT NULL,
    terms_version varchar(32),
    allergen_disclaimer_version varchar(32),
    accepted_at timestamp(6) without time zone NOT NULL,
    ip_address varchar(64),
    user_agent varchar(512)
);

CREATE INDEX IF NOT EXISTS idx_legal_acceptance_backoffice
    ON legal_acceptance(type, restaurant_id, contract_version, privacy_version);

CREATE INDEX IF NOT EXISTS idx_legal_acceptance_customer
    ON legal_acceptance(type, session_id, terms_version, privacy_version, allergen_disclaimer_version);

CREATE INDEX IF NOT EXISTS idx_legal_acceptance_accepted_at
    ON legal_acceptance(accepted_at);
