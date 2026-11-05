package com.hackatlas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountLifecycleMigrationIT {
    @Test
    void upgradesRealV7WithoutInventingVerificationOrChangingExistingCredentialsAndKeepsNewTablesPrivate() throws Exception {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse(
                "postgres@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5"))
                .withUsername("lifecycle_migration_test").withPassword(UUID.randomUUID().toString()).withReuse(false)) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).defaultSchema("public").createSchemas(false)
                    .target("7").cleanDisabled(true).load().migrate();
            UUID user = UUID.randomUUID();
            var now = Timestamp.from(Instant.parse("2026-11-03T10:00:00Z"));
            jdbc.update("""
                    INSERT INTO hackatlas.user_account(id,email,password_hash,display_name,created_at,updated_at)
                    VALUES (?,?,?,'Legacy',?,?)
                    """, user, "legacy@example.com", "synthetic-preserved-password-hash", now, now);
            jdbc.update("""
                    INSERT INTO hackatlas.auth_session(id,user_id,token_hash,created_at,expires_at)
                    VALUES (?,?,?, ?, ?::timestamptz + interval '30 days')
                    """, UUID.randomUUID(), user, "a".repeat(64), now, now);

            var migration = Flyway.configure().dataSource(dataSource).defaultSchema("public")
                    .createSchemas(false).cleanDisabled(true).load();
            migration.migrate();
            assertThat(jdbc.queryForObject("SELECT version FROM public.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                    .isEqualTo("8");
            var row = jdbc.queryForMap("SELECT email,password_hash,email_delivery_address,email_verified,credential_version,password_reset_required FROM hackatlas.user_account WHERE id=?", user);
            assertThat(row.get("email")).isEqualTo("legacy@example.com");
            assertThat(row.get("password_hash")).isEqualTo("synthetic-preserved-password-hash");
            assertThat(row.get("email_delivery_address")).isNull();
            assertThat(row.get("email_verified")).isEqualTo(false);
            assertThat(row.get("credential_version")).isEqualTo(0L);
            assertThat(row.get("password_reset_required")).isEqualTo(false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM hackatlas.auth_session WHERE user_id=? AND revoked_at IS NULL", Integer.class, user)).isEqualTo(1);

            assertThat(jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema='hackatlas' AND table_name IN ('email_action_challenge','password_reset_grant')
                    """, String.class)).doesNotContain("code", "challenge_id", "reset_token", "email");
            jdbc.update("""
                    INSERT INTO hackatlas.email_action_challenge(id_hash,user_id,purpose,credential_version,code_mac,created_at,accepted_at,expires_at)
                    VALUES (?,?,'PASSWORD_RESET',0,?,?,?,?::timestamptz + interval '10 minutes')
                    """, "b".repeat(64), user, "c".repeat(64), now, now, now);
            assertThatThrownBy(() -> jdbc.update("UPDATE hackatlas.email_action_challenge SET failed_attempts=6"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE hackatlas.email_action_challenge SET purpose='LOGIN'"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE hackatlas.email_action_challenge SET code_mac='01234567'"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE hackatlas.email_action_challenge SET expires_at=created_at"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("""
                    INSERT INTO hackatlas.password_reset_grant(token_hash,challenge_hash,user_id,credential_version,created_at,expires_at)
                    VALUES (?,?,?,0,?,?::timestamptz + interval '5 minutes')
                    """, "d".repeat(64), "b".repeat(64), user, now, now);
            assertThatThrownBy(() -> jdbc.update("UPDATE hackatlas.password_reset_grant SET credential_version=1"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // No new runtime privileges are silently added for an inactive feature.
            jdbc.execute("CREATE ROLE hackatlas_runtime NOLOGIN");
            jdbc.execute(Files.readString(Path.of("../infra/release/database/flyway/afterMigrate.sql")));
            for (String table : new String[]{"email_action_challenge", "password_reset_grant"}) {
                for (String privilege : new String[]{"SELECT", "INSERT", "UPDATE", "DELETE"}) {
                    assertThat(jdbc.queryForObject("SELECT has_table_privilege('hackatlas_runtime',?,?)", Boolean.class,
                            "hackatlas." + table, privilege)).isFalse();
                }
            }
            jdbc.update("UPDATE hackatlas.user_account SET email_verified=true,credential_version=2,password_reset_required=true WHERE id=?", user);
            migration.migrate();
            migration.validate();
            assertThat(jdbc.queryForObject("SELECT email_verified AND password_reset_required AND credential_version=2 FROM hackatlas.user_account WHERE id=?", Boolean.class, user))
                    .isTrue();
            jdbc.update("DELETE FROM hackatlas.user_account WHERE id=?", user);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM hackatlas.email_action_challenge", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM hackatlas.password_reset_grant", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM hackatlas.auth_session", Integer.class)).isZero();
        }
    }
}
