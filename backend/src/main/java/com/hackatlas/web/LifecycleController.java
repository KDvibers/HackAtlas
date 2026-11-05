package com.hackatlas.web;

import java.time.Instant;

import com.hackatlas.auth.SessionPrincipal;
import com.hackatlas.auth.lifecycle.LifecycleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "hackatlas.lifecycle.enabled", havingValue = "true")
class LifecycleController {
    private final LifecycleService service;
    private final JsonRequests parser;

    LifecycleController(LifecycleService service, JsonRequests parser) { this.service = service; this.parser = parser; }

    record ChallengeResponse(String challengeId, Instant expiresAt, int resendAfterSeconds) {
        @Override public String toString() { return "EmailChallengeResponse[REDACTED]"; }
    }
    record GrantResponse(String resetToken, Instant expiresAt) {
        @Override public String toString() { return "PasswordResetGrantResponse[REDACTED]"; }
    }

    @PostMapping("/email-verification/request")
    ResponseEntity<?> requestVerification(@AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        parser.noBody(request);
        var result = service.requestVerification(principal, request.getRemoteAddr());
        return result.isEmpty() ? empty() : ResponseEntity.accepted().header("Cache-Control", "no-store")
                .body(new ChallengeResponse(result.get().challengeId(), result.get().expiresAt(), 60));
    }

    @PostMapping("/email-verification/confirm")
    ResponseEntity<Void> confirmVerification(@AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        var input = parser.emailCode(request);
        service.confirmVerification(principal, input.challengeId(), input.code(), request.getRemoteAddr());
        return empty();
    }

    @PostMapping("/password-reset/request")
    ResponseEntity<ChallengeResponse> requestReset(HttpServletRequest request) {
        var result = service.requestReset(parser.resetEmail(request), request.getRemoteAddr());
        return ResponseEntity.accepted().header("Cache-Control", "no-store")
                .body(new ChallengeResponse(result.challengeId(), result.expiresAt(), 60));
    }

    @PostMapping("/password-reset/verify")
    ResponseEntity<GrantResponse> verifyReset(HttpServletRequest request) {
        var input = parser.emailCode(request);
        var result = service.verifyReset(input.challengeId(), input.code(), request.getRemoteAddr());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new GrantResponse(result.resetToken(), result.expiresAt()));
    }

    @PostMapping("/password-reset/confirm")
    ResponseEntity<Void> confirmReset(HttpServletRequest request) {
        var input = parser.resetPassword(request);
        service.confirmReset(input.resetToken(), input.newPassword(), request.getRemoteAddr());
        return empty();
    }

    private static ResponseEntity<Void> empty() { return ResponseEntity.noContent().header("Cache-Control", "no-store").build(); }
}
