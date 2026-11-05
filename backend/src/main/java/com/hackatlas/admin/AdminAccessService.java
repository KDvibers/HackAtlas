package com.hackatlas.admin;

import java.util.UUID;
import com.hackatlas.web.ApiFailure;
import com.hackatlas.auth.lifecycle.LifecycleSettings;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AdminAccessService {
    private final JdbcClient jdbc;
    private final LifecycleSettings lifecycle;

    public AdminAccessService(JdbcClient jdbc, LifecycleSettings lifecycle) { this.jdbc = jdbc; this.lifecycle = lifecycle; }

    public boolean allowed(AdminPrincipal principal) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM hackatlas.user_account u JOIN hackatlas.admin_access a ON a.user_id=u.id
                WHERE u.id=:user AND u.credential_version=:version AND NOT u.password_reset_required
                    AND (:enforce=FALSE OR u.email_verified))
                """).param("user", principal.userId()).param("version", principal.credentialVersion())
                .param("enforce", lifecycle.enabled()).query(Boolean.class).single();
    }

    /** Rechecks membership inside the editing transaction and serializes account deletion. */
    public void requireForMutation(AdminPrincipal principal) {
        // Account deletion takes this same account lock before cascading into admin_access.
        // Do not require UPDATE on admin_access just to lock it: runtime must not grant or
        // reassign admin membership. Revocation rejects the next check; an in-flight action
        // that already passed this check may finish.
        var account = jdbc.sql("SELECT credential_version,email_verified,password_reset_required FROM hackatlas.user_account WHERE id=:user FOR SHARE")
                .param("user",principal.userId()).query((rs,row) -> new Access(rs.getLong(1),rs.getBoolean(2),rs.getBoolean(3))).optional();
        if (account.isEmpty() || account.get().version() != principal.credentialVersion() || account.get().resetRequired()
                || lifecycle.enabled() && !account.get().verified() || jdbc.sql("SELECT user_id FROM hackatlas.admin_access WHERE user_id=:user")
                .param("user",principal.userId()).query(UUID.class).optional().isEmpty()) {
            throw new ApiFailure(403, "FORBIDDEN", "Административный доступ закрыт.");
        }
    }
    private record Access(long version, boolean verified, boolean resetRequired) { }
}
