package com.hackatlas.auth.lifecycle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class LifecycleDatabaseGuard {
    public LifecycleDatabaseGuard(JdbcTemplate jdbc, LifecycleSettings settings) {
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT lifecycle_activated FROM hackatlas.account_security_state WHERE singleton", Boolean.class));
        if (active && !settings.enabled()) {
            throw new IllegalStateException("Lifecycle database cannot start in legacy mode");
        }
        if (settings.enabled()) {
            String database = jdbc.queryForObject("SELECT current_database()", String.class);
            if (database == null || !database.matches("hackatlas_(?:restore_)?lifecycle_test(?:_[a-z0-9]+)?")) {
                throw new IllegalStateException("Closed lifecycle requires a dedicated lifecycle test database");
            }
            jdbc.update("UPDATE hackatlas.account_security_state SET lifecycle_activated=TRUE WHERE singleton");
        }
    }
}
