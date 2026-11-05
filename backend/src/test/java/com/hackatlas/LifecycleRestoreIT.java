package com.hackatlas;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.hackatlas.auth.AuthRateLimiter;
import com.hackatlas.auth.AuthService;
import com.hackatlas.auth.lifecycle.*;
import com.hackatlas.web.ApiFailure;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

class LifecycleRestoreIT {
    @TempDir Path privateFiles;

    @Test
    void genuineV8BackupRequiresPreparedRecoveryAndCannotReviveOldPasswordOrReplayPreparation() throws Exception {
        try (var pg = LifecycleContainers.postgres("lifecycle_restore_owner");
             var mail = LifecycleContainers.mailpit()) {
            pg.start(); mail.start();
            var source = new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE ROLE hackatlas_runtime NOLOGIN");
            Flyway.configure().dataSource(source).defaultSchema("public").createSchemas(false).target("8").load().migrate();
            UUID user = UUID.randomUUID();
            String email = "restore-" + UUID.randomUUID() + "@example.com";
            var passwords = new Argon2PasswordEncoder(16,32,1,19456,2);
            String oldPassword = "old-synthetic-before-backup";
            String oldHash = passwords.encode(oldPassword);
            jdbc.update("""
                    INSERT INTO hackatlas.user_account(id,email,email_delivery_address,password_hash,display_name,created_at,updated_at,email_verified)
                    VALUES(?,?,?,?,'Restore',now(),now(),TRUE)
                    """,user,email,email,oldHash);
            jdbc.update("INSERT INTO hackatlas.admin_access(user_id) VALUES(?)",user);
            jdbc.update("INSERT INTO hackatlas.auth_session(id,user_id,token_hash,created_at,expires_at) VALUES(?,?,?,now(),now()+interval '1 day')",
                    UUID.randomUUID(),user,"a".repeat(64));
            jdbc.update("""
                    INSERT INTO hackatlas.email_action_challenge(id_hash,user_id,purpose,credential_version,code_mac,created_at,accepted_at,expires_at)
                    VALUES(?,?,'PASSWORD_RESET',0,?,now(),now(),now()+interval '10 minutes')
                    ""","b".repeat(64),user,"c".repeat(64));
            jdbc.update("""
                    INSERT INTO hackatlas.password_reset_grant(token_hash,challenge_hash,user_id,credential_version,created_at,expires_at)
                    VALUES(?,?,?,0,now(),now()+interval '5 minutes')
                    ""","d".repeat(64),"b".repeat(64),user);
            docker(pg,"pg_dump","-U",pg.getUsername(),"-d",pg.getDatabaseName(),"-Fc","-f","/tmp/n2-old.dump");
            // The source password changes after the snapshot, so an unprepared restore would revive it.
            jdbc.update("UPDATE hackatlas.user_account SET password_hash=?,credential_version=1 WHERE id=?",
                    passwords.encode("newer-source-synthetic-password"),user);
            String target = "hackatlas_restore_lifecycle_test_" + UUID.randomUUID().toString().replace("-","").substring(0,16);
            jdbc.execute("CREATE DATABASE \"" + target + "\"");
            jdbc.execute("REVOKE CONNECT ON DATABASE \"" + target + "\" FROM PUBLIC,hackatlas_runtime");
            docker(pg,"pg_restore","-U",pg.getUsername(),"--no-owner","--no-acl","--exit-on-error","-d",target,"/tmp/n2-old.dump");
            var restoredData = new DriverManagerDataSource(pg.getJdbcUrl().replace("/"+pg.getDatabaseName(),"/"+target),pg.getUsername(),pg.getPassword());
            var restored = new JdbcTemplate(restoredData);
            assertThat(restored.queryForObject("SELECT password_hash FROM hackatlas.user_account WHERE id=?",String.class,user)).isEqualTo(oldHash);
            UUID restoreId = UUID.randomUUID();
            String state = "{\"kind\":\"hackatlas.restore\",\"formatVersion\":1,\"project\":\"n2-restore-test\",\"database\":\""+target
                    +"\",\"backupSha256\":\""+"f".repeat(64)+"\",\"restoreId\":\""+restoreId
                    +"\",\"state\":\"QUARANTINED\",\"restoredAt\":\"2026-11-03T10:00:00Z\",\"lifecycleRecoveryRequired\":true}";
            restored.execute("COMMENT ON DATABASE \""+target+"\" IS '"+state+"'");
            var migrations = Flyway.configure().dataSource(restoredData).defaultSchema("public").createSchemas(false).load();
            migrations.migrate();
            assertThatThrownBy(() -> restored.queryForObject("SELECT hackatlas.require_lifecycle_restore_prepared(?)",Object.class,restoreId))
                    .isInstanceOf(DataAccessException.class);
            restored.queryForObject("SELECT hackatlas.prepare_lifecycle_restore(?)",Object.class,restoreId);
            restored.queryForObject("SELECT hackatlas.require_lifecycle_restore_prepared(?)",Object.class,restoreId);
            restored.execute(Files.readString(Path.of("../infra/release/database/flyway/afterMigrate.sql")));
            assertThat(restored.queryForObject("SELECT password_reset_required AND NOT email_verified AND credential_version=1 FROM hackatlas.user_account WHERE id=?",Boolean.class,user)).isTrue();
            for (String table : new String[]{"auth_session","email_action_challenge","password_reset_grant"}) {
                assertThat(restored.queryForObject("SELECT count(*) FROM hackatlas."+table,Integer.class)).isZero();
            }
            assertThat(restored.queryForObject("SELECT has_database_privilege('hackatlas_runtime',current_database(),'CONNECT')",Boolean.class)).isFalse();
            assertThat(restored.queryForObject("SELECT has_function_privilege('hackatlas_runtime','hackatlas.prepare_lifecycle_restore(uuid)','EXECUTE')",Boolean.class)).isFalse();
            assertThatThrownBy(() -> restored.queryForObject("SELECT hackatlas.prepare_lifecycle_restore(?)",Object.class,UUID.randomUUID()))
                    .isInstanceOf(DataAccessException.class);

            var env = new MockEnvironment(); env.setActiveProfiles("test");
            var oldAuth = new AuthService(restored,Clock.systemUTC(),passwords,
                    new AuthRateLimiter(Clock.systemUTC(),Duration.ofMinutes(15),100,100,100),env,new LifecycleSettings(env),false,Duration.ofDays(30));
            assertThat(catchThrowableOfType(() -> oldAuth.login(email,oldPassword),ApiFailure.class).code()).isEqualTo("PASSWORD_RESET_REQUIRED");

            // Simulate operator approval only after the real SQL preparation gate passes.
            String approved = state.replace("QUARANTINED","APPROVED_FOR_SMOKE");
            restored.execute("COMMENT ON DATABASE \""+target+"\" IS '"+approved+"'");
            restored.execute("GRANT CONNECT ON DATABASE \""+target+"\" TO hackatlas_runtime");
            Path key = privateFiles.resolve("key.bin"); Files.write(key,new byte[32]);
            if (Files.getFileStore(key).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-------"));
            env.setActiveProfiles("test","lifecycle-test");
            env.setProperty("hackatlas.lifecycle.enabled","true");
            env.setProperty("hackatlas.lifecycle.key-file",key.toString());
            env.setProperty("hackatlas.lifecycle.smtp-port",mail.getMappedPort(1025).toString());
            var settings = new LifecycleSettings(env);
            new LifecycleDatabaseGuard(restored,settings);
            var crypto = new EmailActionCrypto(settings.hmacKey());
            var metrics = new SimpleMeterRegistry();
            try (var queue = new LifecycleMailQueue(4); var inbox = HttpClient.newHttpClient()) {
                var service = new LifecycleService(restored,Clock.systemUTC(),new EmailActionStore(restored,Clock.systemUTC(),crypto),crypto,
                        queue,new LifecycleMailer(settings),new LifecycleRateLimiter(Clock.systemUTC(),10,5,30,100),passwords,
                        new DataSourceTransactionManager(restoredData),metrics);
                var challenge = service.requestReset(email,"192.0.2.1");
                String[] content = new String[1];
                await().atMost(12,TimeUnit.SECONDS).until(() -> {
                    var response = inbox.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+mail.getMappedPort(8025)
                            +"/view/latest.txt?query="+URLEncoder.encode("to:"+email,StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
                    content[0]=response.body(); return response.statusCode()==200;
                });
                var matcher=Pattern.compile("Код: ([0-9]{8})").matcher(content[0]);
                assertThat(matcher.find()).isTrue();
                var grant=service.verifyReset(challenge.challengeId(),matcher.group(1),"192.0.2.1");
                service.confirmReset(grant.resetToken(),"fresh-after-restore-synthetic-password","192.0.2.1");
                assertThat(restored.queryForObject("SELECT NOT password_reset_required AND email_verified AND credential_version=2 FROM hackatlas.user_account WHERE id=?",Boolean.class,user)).isTrue();
                assertThat(catchThrowableOfType(() -> oldAuth.login(email,oldPassword),ApiFailure.class).status()).isEqualTo(401);
                assertThat(oldAuth.login(email,"fresh-after-restore-synthetic-password").userId()).isEqualTo(user);
            } finally { metrics.close(); }
            migrations.migrate();
            restored.execute(Files.readString(Path.of("../infra/release/database/flyway/afterMigrate.sql")));
            assertThat(restored.queryForObject("SELECT password_reset_required FROM hackatlas.user_account WHERE id=?",Boolean.class,user)).isFalse();
            assertThatThrownBy(() -> new LifecycleDatabaseGuard(restored,new LifecycleSettings(new MockEnvironment())))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void restoredV9FunctionsRegainOwnerOnlyPermissionsWhenSnapshotAclWasIntentionallyOmitted() throws Exception {
        try (var pg = LifecycleContainers.postgres("restore_acl_owner")) {
            pg.start();
            var source = new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE ROLE hackatlas_runtime NOLOGIN");
            Flyway.configure().dataSource(source).defaultSchema("public").createSchemas(false).load().migrate();
            jdbc.update("UPDATE hackatlas.account_security_state SET lifecycle_activated=TRUE");
            docker(pg,"pg_dump","-U",pg.getUsername(),"-d",pg.getDatabaseName(),"-Fc","-f","/tmp/n2-v9.dump");
            String target="hackatlas_restore_lifecycle_test_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
            jdbc.execute("CREATE DATABASE \""+target+"\"");
            jdbc.execute("REVOKE CONNECT ON DATABASE \""+target+"\" FROM PUBLIC,hackatlas_runtime");
            docker(pg,"pg_restore","-U",pg.getUsername(),"--no-owner","--no-acl","--exit-on-error","-d",target,"/tmp/n2-v9.dump");
            var restored=new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl().replace("/"+pg.getDatabaseName(),"/"+target),pg.getUsername(),pg.getPassword()));
            var restoreId=UUID.randomUUID();
            String state="{\"kind\":\"hackatlas.restore\",\"formatVersion\":1,\"database\":\""+target
                    +"\",\"restoreId\":\""+restoreId+"\",\"state\":\"QUARANTINED\",\"lifecycleRecoveryRequired\":true}";
            restored.execute("COMMENT ON DATABASE \""+target+"\" IS '"+state+"'");
            // pg_restore --no-acl intentionally resets ACL. The existing callback must reassert function deny rules too.
            restored.execute(Files.readString(Path.of("../infra/release/database/flyway/afterMigrate.sql")));
            assertThat(restored.queryForObject("SELECT has_function_privilege('hackatlas_runtime','hackatlas.prepare_lifecycle_restore(uuid)','EXECUTE')",Boolean.class)).isFalse();
            assertThat(restored.queryForObject("SELECT has_function_privilege('hackatlas_runtime','hackatlas.require_lifecycle_restore_prepared(uuid)','EXECUTE')",Boolean.class)).isFalse();
            restored.queryForObject("SELECT hackatlas.require_lifecycle_restore_prepared(?)",Object.class,restoreId);
        }
    }

    private static void docker(PostgreSQLContainer pg,String... arguments) throws Exception {
        var command=new java.util.ArrayList<>(java.util.List.of("docker","exec","--user","999:999",pg.getContainerId()));
        command.addAll(java.util.List.of(arguments));
        var process=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(process.waitFor(30,TimeUnit.SECONDS)).as("disposable PostgreSQL archive command completed").isTrue();
        assertThat(process.exitValue()).as("disposable PostgreSQL archive command exit code").isZero();
    }
}
