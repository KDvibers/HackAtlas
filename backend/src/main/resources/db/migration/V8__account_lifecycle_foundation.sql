-- Inactive lifecycle foundation. Existing accounts are NOT verified and their
-- original delivery address cannot be reconstructed from the normalized email.
ALTER TABLE hackatlas.user_account
    ADD COLUMN email_delivery_address VARCHAR(254),
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0 CHECK (credential_version >= 0),
    ADD COLUMN password_reset_required BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE hackatlas.email_action_challenge (
    id_hash CHAR(64) PRIMARY KEY CHECK (id_hash ~ '^[0-9a-f]{64}$'),
    user_id UUID NOT NULL REFERENCES hackatlas.user_account(id) ON DELETE CASCADE,
    purpose VARCHAR(24) NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    credential_version BIGINT NOT NULL CHECK (credential_version >= 0),
    code_mac CHAR(64) NOT NULL CHECK (code_mac ~ '^[0-9a-f]{64}$'),
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    created_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    UNIQUE (id_hash, user_id, credential_version),
    CHECK (expires_at = created_at + INTERVAL '10 minutes'),
    CHECK (accepted_at >= created_at AND accepted_at < expires_at),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

CREATE INDEX email_action_user_purpose_idx
    ON hackatlas.email_action_challenge (user_id, purpose, credential_version, accepted_at);
CREATE INDEX email_action_expiry_idx ON hackatlas.email_action_challenge (expires_at);

CREATE TABLE hackatlas.password_reset_grant (
    token_hash CHAR(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    challenge_hash CHAR(64) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES hackatlas.user_account(id) ON DELETE CASCADE,
    credential_version BIGINT NOT NULL CHECK (credential_version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    FOREIGN KEY (challenge_hash, user_id, credential_version)
        REFERENCES hackatlas.email_action_challenge (id_hash, user_id, credential_version) ON DELETE CASCADE,
    CHECK (expires_at = created_at + INTERVAL '5 minutes'),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

CREATE INDEX password_reset_grant_user_idx ON hackatlas.password_reset_grant (user_id);
CREATE INDEX password_reset_grant_expiry_idx ON hackatlas.password_reset_grant (expires_at);
