package com.hackatlas.web;

import com.hackatlas.auth.AccountDeletionService;
import com.hackatlas.auth.SessionPrincipal;
import com.hackatlas.moderation.ModerationRateLimiter;
import com.hackatlas.moderation.ModerationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ModerationController {
    private final ModerationService moderation;
    private final ModerationRateLimiter limiter;
    private final AccountDeletionService accounts;
    private final JsonRequests requests;

    ModerationController(ModerationService moderation, ModerationRateLimiter limiter,
            AccountDeletionService accounts, JsonRequests requests) {
        this.moderation = moderation;
        this.limiter = limiter;
        this.accounts = accounts;
        this.requests = requests;
    }

    @PostMapping("/suggestions")
    ResponseEntity<ModerationService.Receipt> suggest(@AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest request) {
        limiter.check(request.getRemoteAddr(), principal.userId());
        var body = requests.suggestion(request);
        return ResponseEntity.accepted().body(moderation.suggest(principal.userId(), body.url(), body.comment()));
    }

    @PostMapping("/hackathons/{id}/reports")
    ResponseEntity<ModerationService.Receipt> report(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable String id, HttpServletRequest request) {
        limiter.check(request.getRemoteAddr(), principal.userId());
        var hackathonId = CatalogQueryParser.id(id);
        var body = requests.report(request);
        return ResponseEntity.accepted().body(moderation.report(principal.userId(), hackathonId, body.reason(), body.message()));
    }

    @PostMapping("/me/delete-account")
    ResponseEntity<Void> delete(@AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        accounts.delete(principal, requests.deletionPassword(request));
        return ResponseEntity.noContent().build();
    }
}
