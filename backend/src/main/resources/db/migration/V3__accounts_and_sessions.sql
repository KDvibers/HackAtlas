CREATE TABLE hackatlas.user_account (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(512) NOT NULL,
    display_name VARCHAR(60) NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 60),
    city VARCHAR(100),
    country_code VARCHAR(2) CHECK (country_code ~ '^[A-Z]{2}$'),
    role_label VARCHAR(100),
    avatar_key VARCHAR(16) NOT NULL DEFAULT 'HOODED' CHECK (avatar_key IN ('HOODED', 'INITIALS')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (email = lower(btrim(email)))
);

CREATE TABLE hackatlas.auth_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES hackatlas.user_account(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);

CREATE INDEX auth_session_user_id_idx ON hackatlas.auth_session (user_id);
CREATE INDEX auth_session_expires_at_idx ON hackatlas.auth_session (expires_at);
