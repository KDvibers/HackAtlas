-- Purpose-limited recovery receipts, not a public audit log or independent delivery.
-- No FK: the account has been erased. No email, credentials, IP or submitted content.
CREATE TABLE hackatlas.account_deletion_receipt (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL UNIQUE,
    recorded_at timestamptz NOT NULL
);
