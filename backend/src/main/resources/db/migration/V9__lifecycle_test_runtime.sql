ALTER TABLE hackatlas.auth_session
    ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0 CHECK (credential_version >= 0);
ALTER TABLE hackatlas.user_account ADD COLUMN credential_changed_at TIMESTAMPTZ;

-- Durable source marker: a database used for lifecycle cannot silently start in legacy mode.
CREATE TABLE hackatlas.account_security_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    lifecycle_activated BOOLEAN NOT NULL DEFAULT FALSE,
    restore_prepared_id UUID,
    CHECK (restore_prepared_id IS NULL OR lifecycle_activated)
);
INSERT INTO hackatlas.account_security_state(singleton) VALUES (TRUE);

-- Owner-only operator boundary. No credentials or user-controlled SQL are arguments.
CREATE FUNCTION hackatlas.prepare_lifecycle_restore(target_id UUID) RETURNS VOID
LANGUAGE plpgsql SET search_path = pg_catalog, hackatlas AS $prepare$
DECLARE metadata JSONB; previous UUID; active BOOLEAN;
BEGIN
    IF target_id IS NULL OR current_database() !~ '^hackatlas_restore_[a-z0-9_]{1,40}$' THEN
        RAISE EXCEPTION 'Lifecycle preparation requires a quarantined restore database';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('hackatlas.lifecycle.restore:' || current_database(), 0));
    SELECT shobj_description(oid, 'pg_database')::jsonb INTO metadata FROM pg_database WHERE datname=current_database();
    IF metadata IS NULL OR metadata->>'kind' IS DISTINCT FROM 'hackatlas.restore'
        OR metadata->'formatVersion' IS DISTINCT FROM '1'::jsonb
        OR metadata->>'database' IS DISTINCT FROM current_database()
        OR metadata->>'state' IS DISTINCT FROM 'QUARANTINED'
        OR metadata->>'restoreId' IS DISTINCT FROM target_id::text
        OR metadata->'lifecycleRecoveryRequired' IS DISTINCT FROM 'true'::jsonb THEN
        RAISE EXCEPTION 'Lifecycle preparation metadata does not match the quarantined restore';
    END IF;
    IF has_database_privilege('hackatlas_runtime', current_database(), 'CONNECT') OR EXISTS(
        SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND usename='hackatlas_runtime'
    ) THEN RAISE EXCEPTION 'Lifecycle restore must remain disconnected'; END IF;
    SELECT restore_prepared_id,lifecycle_activated INTO previous,active
        FROM hackatlas.account_security_state WHERE singleton FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Missing lifecycle source state'; END IF;
    IF previous=target_id THEN
        IF NOT active THEN RAISE EXCEPTION 'Lifecycle source state was disabled'; END IF;
        RETURN;
    END IF;
    UPDATE hackatlas.user_account SET password_reset_required=TRUE,email_verified=FALSE,
        credential_version=credential_version+1,credential_changed_at=clock_timestamp();
    DELETE FROM hackatlas.auth_session;
    DELETE FROM hackatlas.password_reset_grant;
    DELETE FROM hackatlas.email_action_challenge;
    UPDATE hackatlas.account_security_state SET lifecycle_activated=TRUE,restore_prepared_id=target_id WHERE singleton;
END;
$prepare$;

CREATE FUNCTION hackatlas.require_lifecycle_restore_prepared(target_id UUID) RETURNS VOID
LANGUAGE plpgsql SET search_path = pg_catalog, hackatlas AS $require$
DECLARE metadata JSONB;
BEGIN
    SELECT shobj_description(oid, 'pg_database')::jsonb INTO metadata FROM pg_database WHERE datname=current_database();
    IF target_id IS NULL OR metadata IS NULL OR metadata->>'kind' IS DISTINCT FROM 'hackatlas.restore'
        OR metadata->>'state' IS DISTINCT FROM 'QUARANTINED'
        OR metadata->>'database' IS DISTINCT FROM current_database()
        OR metadata->>'restoreId' IS DISTINCT FROM target_id::text
        OR metadata->'lifecycleRecoveryRequired' IS DISTINCT FROM 'true'::jsonb THEN
        RAISE EXCEPTION 'Lifecycle approval metadata does not match';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM hackatlas.account_security_state
        WHERE singleton AND lifecycle_activated AND restore_prepared_id=target_id)
        OR EXISTS(SELECT 1 FROM hackatlas.user_account WHERE NOT password_reset_required)
        OR EXISTS(SELECT 1 FROM hackatlas.auth_session)
        OR EXISTS(SELECT 1 FROM hackatlas.email_action_challenge)
        OR EXISTS(SELECT 1 FROM hackatlas.password_reset_grant) THEN
        RAISE EXCEPTION 'Lifecycle restore preparation is incomplete';
    END IF;
END;
$require$;
REVOKE ALL ON FUNCTION hackatlas.prepare_lifecycle_restore(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION hackatlas.require_lifecycle_restore_prepared(UUID) FROM PUBLIC;
