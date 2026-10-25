CREATE TABLE hackatlas.admin_access (
    user_id uuid PRIMARY KEY REFERENCES hackatlas.user_account(id) ON DELETE CASCADE
);

CREATE TABLE hackatlas.admin_audit (
    id uuid PRIMARY KEY,
    actor_id uuid REFERENCES hackatlas.user_account(id) ON DELETE SET NULL,
    action varchar(64) NOT NULL,
    event_id uuid REFERENCES hackatlas.catalog_hackathon(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX admin_audit_created_at_idx ON hackatlas.admin_audit (created_at DESC);

-- Privileges are granted by an operator, never by registration, profile labels or demo seeding.
