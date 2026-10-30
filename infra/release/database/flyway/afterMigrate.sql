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
-- New tables remain inaccessible until the next release explicitly reviews this allowlist.
