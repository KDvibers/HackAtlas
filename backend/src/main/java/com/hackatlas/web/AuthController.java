package com.hackatlas.web;

import java.time.Instant;
import com.hackatlas.auth.AuthService;
import com.hackatlas.auth.IssuedSession;
import com.hackatlas.auth.SessionPrincipal;
import com.hackatlas.personal.PersonalDtos;
import com.hackatlas.personal.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final ProfileService profiles;
    private final JsonRequests requests;

    AuthController(AuthService auth, ProfileService profiles, JsonRequests requests) {
        this.auth = auth;
        this.profiles = profiles;
        this.requests = requests;
    }

    public record AuthResponse(String tokenType, String accessToken, Instant expiresAt, PersonalDtos.Profile user) {
        @Override public String toString() { return "AuthResponse[redacted]"; }
    }

    @PostMapping("/register")
    ResponseEntity<AuthResponse> register(HttpServletRequest request) {
        var input = requests.registration(request);
        return issued(auth.register(input.email(), input.password(), input.displayName()), 201);
    }

    @PostMapping("/login")
    ResponseEntity<AuthResponse> login(HttpServletRequest request) {
        var input = requests.login(request);
        return issued(auth.login(input.email(), input.password()), 200);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal SessionPrincipal principal) {
        auth.logout(principal);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<AuthResponse> issued(IssuedSession session, int status) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(new AuthResponse("Bearer", session.accessToken(), session.expiresAt(), profiles.get(session.userId())));
    }
}
