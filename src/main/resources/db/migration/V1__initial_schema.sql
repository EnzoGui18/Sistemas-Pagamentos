CREATE TABLE clients (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_clients_name_not_blank CHECK (BTRIM(name) <> ''),
    CONSTRAINT ck_clients_email_normalized CHECK (email = LOWER(BTRIM(email))),
    CONSTRAINT uq_clients_email UNIQUE (email)
);

CREATE TABLE charges (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    description VARCHAR(200) NOT NULL,
    amount NUMERIC(15, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    due_date DATE NOT NULL,
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_charges_client FOREIGN KEY (client_id) REFERENCES clients (id),
    CONSTRAINT ck_charges_description_not_blank CHECK (BTRIM(description) <> ''),
    CONSTRAINT ck_charges_amount CHECK (amount > 0 AND amount <= 1000000.00),
    CONSTRAINT ck_charges_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_charges_status CHECK (status IN ('PENDING', 'PAID', 'CANCELED')),
    CONSTRAINT ck_charges_version CHECK (version >= 0),
    CONSTRAINT ck_charges_updated_at CHECK (updated_at >= created_at)
);

CREATE INDEX idx_charges_client_id ON charges (client_id);
CREATE INDEX idx_charges_status ON charges (status);
CREATE INDEX idx_charges_due_date ON charges (due_date);

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    charge_id UUID NOT NULL,
    amount NUMERIC(15, 2) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    paid_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_payments_charge FOREIGN KEY (charge_id) REFERENCES charges (id),
    CONSTRAINT uq_payments_charge_id UNIQUE (charge_id),
    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_payments_amount CHECK (amount > 0 AND amount <= 1000000.00),
    CONSTRAINT ck_payments_idempotency_key_not_blank CHECK (BTRIM(idempotency_key) <> '')
);

CREATE TABLE charge_events (
    id UUID PRIMARY KEY,
    charge_id UUID NOT NULL,
    type VARCHAR(10) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    details JSONB,
    CONSTRAINT fk_charge_events_charge FOREIGN KEY (charge_id) REFERENCES charges (id),
    CONSTRAINT ck_charge_events_type CHECK (type IN ('CREATED', 'PAID', 'CANCELED'))
);

CREATE INDEX idx_charge_events_charge_occurred_at
    ON charge_events (charge_id, occurred_at, id);
