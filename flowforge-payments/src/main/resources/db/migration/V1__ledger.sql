-- The simulated payment provider's ledger. It lives in its own schema: it stands for an external system that
-- FlowForge cannot roll back, only call again.

CREATE TABLE payments.charge (
    id               uuid          PRIMARY KEY,
    -- The caller's idempotency key: FlowForge passes the step execution id of the charge step.
    idempotency_key  varchar(100)  NOT NULL UNIQUE,
    order_id         varchar(100)  NOT NULL,
    amount           numeric(14, 2) NOT NULL CHECK (amount > 0),
    status           varchar(20)   NOT NULL CHECK (status IN ('CAPTURED', 'DECLINED', 'REFUNDED')),
    decline_reason   varchar(200),
    created_at       timestamptz   NOT NULL,
    refunded_at      timestamptz,
    -- How many requests carried this key: everything above 1 is a duplicate that was not charged again.
    requests         int           NOT NULL DEFAULT 1 CHECK (requests >= 1),
    last_request_at  timestamptz   NOT NULL,
    CONSTRAINT ck_charge_refunded CHECK ((status = 'REFUNDED') = (refunded_at IS NOT NULL))
);

CREATE INDEX idx_charge_order ON payments.charge (order_id);
CREATE INDEX idx_charge_created ON payments.charge (created_at DESC);

CREATE TABLE payments.refund (
    id               uuid          PRIMARY KEY,
    idempotency_key  varchar(100)  NOT NULL UNIQUE,
    charge_id        uuid          NOT NULL UNIQUE REFERENCES payments.charge (id),
    amount           numeric(14, 2) NOT NULL,
    created_at       timestamptz   NOT NULL,
    requests         int           NOT NULL DEFAULT 1 CHECK (requests >= 1),
    last_request_at  timestamptz   NOT NULL
);
