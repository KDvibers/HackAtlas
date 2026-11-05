-- Deployment-local permissions, executed as object owner after Flyway.
-- No runtime access to Flyway history, schema DDL, role grants or audit deletion.
REVOKE ALL ON SCHEMA hackatlas FROM PUBLIC;
GRANT USAGE ON SCHEMA hackatlas TO hackatlas_runtime;
REVOKE ALL ON ALL TABLES IN SCHEMA hackatlas FROM hackatlas_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON
    hackatlas.user_account, hackatlas.user_interest, hackatlas.saved_hackathon,
    hackatlas.application_mark
    TO hackatlas_runtime;
GRANT SELECT, INSERT, UPDATE ON
    hackatlas.auth_session, hackatlas.moderation_submission, hackatlas.catalog_hackathon
    TO hackatlas_runtime;
GRANT SELECT ON hackatlas.catalog_tag, hackatlas.catalog_hackathon_topic, hackatlas.admin_access
    TO hackatlas_runtime;
GRANT INSERT ON hackatlas.admin_audit TO hackatlas_runtime;
-- restore.py also applies this allowlist before upgrading a legacy V6 snapshot.
DO $hackatlas_receipt_acl$
BEGIN
    IF to_regclass('hackatlas.account_deletion_receipt') IS NOT NULL THEN
        GRANT INSERT ON hackatlas.account_deletion_receipt TO hackatlas_runtime;
    END IF;
END
$hackatlas_receipt_acl$;
REVOKE ALL ON TABLE public.flyway_schema_history FROM hackatlas_runtime;
DO $hackatlas_lifecycle_restore$
DECLARE raw TEXT; metadata JSONB; active BOOLEAN := FALSE;
BEGIN
    -- pg_restore --no-acl restores default PUBLIC function EXECUTE, so reassert these
    -- owner-only boundaries even when V9 is already present and no migration runs.
    IF to_regprocedure('hackatlas.prepare_lifecycle_restore(uuid)') IS NOT NULL THEN
        REVOKE ALL ON FUNCTION hackatlas.prepare_lifecycle_restore(UUID) FROM PUBLIC, hackatlas_runtime;
    END IF;
    IF to_regprocedure('hackatlas.require_lifecycle_restore_prepared(uuid)') IS NOT NULL THEN
        REVOKE ALL ON FUNCTION hackatlas.require_lifecycle_restore_prepared(UUID) FROM PUBLIC, hackatlas_runtime;
    END IF;
    IF to_regclass('hackatlas.account_security_state') IS NOT NULL THEN
        GRANT SELECT ON hackatlas.account_security_state TO hackatlas_runtime;
        EXECUTE 'SELECT lifecycle_activated FROM hackatlas.account_security_state WHERE singleton' INTO active;
    END IF;
    SELECT shobj_description(oid, 'pg_database') INTO raw FROM pg_database WHERE datname=current_database();
    IF raw IS NULL OR left(ltrim(raw),1) <> '{' THEN RETURN; END IF;
    BEGIN metadata := raw::jsonb; EXCEPTION WHEN invalid_text_representation THEN RETURN; END;
    IF metadata->>'kind'='hackatlas.restore' AND metadata->>'state'='QUARANTINED'
        AND (metadata->'lifecycleRecoveryRequired'='true'::jsonb OR active) THEN
        IF to_regprocedure('hackatlas.prepare_lifecycle_restore(uuid)') IS NULL THEN
            IF active THEN RAISE EXCEPTION 'Missing lifecycle restore preparation'; END IF;
            RETURN; -- A genuine old snapshot stays closed until it has migrated to V9.
        END IF;
        EXECUTE 'SELECT hackatlas.prepare_lifecycle_restore($1)' USING (metadata->>'restoreId')::uuid;
    END IF;
END;
$hackatlas_lifecycle_restore$;
-- New tables remain inaccessible until the next release explicitly reviews this allowlist.
