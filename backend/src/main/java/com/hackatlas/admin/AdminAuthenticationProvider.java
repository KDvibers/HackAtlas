package com.hackatlas.admin;

import java.util.List;
import java.util.UUID;
import com.hackatlas.auth.AuthRateLimiter;
import com.hackatlas.auth.AuthService;
import com.hackatlas.auth.lifecycle.LifecycleSettings;
import com.hackatlas.web.ApiFailure;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminAuthenticationProvider implements AuthenticationProvider {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final AuthRateLimiter limiter;
    private final String dummyHash;
    private final LifecycleSettings lifecycle;

    public AdminAuthenticationProvider(JdbcClient jdbc, PasswordEncoder passwords, AuthRateLimiter limiter, LifecycleSettings lifecycle) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.limiter = limiter;
        this.lifecycle = lifecycle;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public Authentication authenticate(Authentication authentication) {
        String email = AuthService.normalizeEmail(authentication.getName());
        String password = authentication.getCredentials() instanceof String value ? value : "";
        if (email.length() > 254 || email.indexOf('\0') >= 0 || !validUnicode(email) || password.isEmpty()
                || password.codePointCount(0, password.length()) > 256 || !validUnicode(password)) {
            throw new BadCredentialsException("Invalid credentials");
        }
        try {
            limiter.checkAccount(email);
            var rows = jdbc.sql("""
                    SELECT u.id, u.password_hash,
                        EXISTS(SELECT 1 FROM hackatlas.admin_access a WHERE a.user_id = u.id) is_admin,
                        u.credential_version,u.email_verified,u.password_reset_required
                    FROM hackatlas.user_account u WHERE u.email = :email FOR UPDATE OF u
                    """).param("email", email).query((rs, row) -> new Credential(rs.getObject(1, UUID.class),
                    rs.getString(2), rs.getBoolean(3), rs.getLong(4), rs.getBoolean(5), rs.getBoolean(6))).list();
            Credential user = rows.isEmpty() ? null : rows.getFirst();
            boolean matches = passwords.matches(password, user == null ? dummyHash : user.hash);
            if (user == null || !matches || !user.admin || user.resetRequired || lifecycle.enabled() && !user.verified) {
                throw new BadCredentialsException("Invalid credentials");
            }
            return UsernamePasswordAuthenticationToken.authenticated(new AdminPrincipal(user.id, user.version), null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        } catch (ApiFailure failure) {
            if (failure.status() == 429) { throw new AdminLoginException(failure.retryAfter()); }
            throw failure;
        } catch (BadCredentialsException exception) {
            throw exception;
        } catch (DataAccessResourceFailureException | CannotCreateTransactionException exception) {
            // Authentication filters must not log database messages or submitted usernames.
            throw new AdminLoginException(503,0);
        } catch (RuntimeException exception) {
            throw new AdminLoginException(500,0);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static boolean validUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) { return false; }
            } else if (Character.isLowSurrogate(c)) { return false; }
        }
        return true;
    }

    private static final class Credential {
        private final UUID id;
        private final String hash;
        private final boolean admin;
        private final long version;
        private final boolean verified;
        private final boolean resetRequired;
        private Credential(UUID id, String hash, boolean admin, long version, boolean verified, boolean resetRequired) {
            this.id = id; this.hash = hash; this.admin = admin; this.version = version;
            this.verified = verified; this.resetRequired = resetRequired;
        }
    }
}
