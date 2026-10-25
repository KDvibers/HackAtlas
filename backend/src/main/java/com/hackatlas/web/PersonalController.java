package com.hackatlas.web;

import com.hackatlas.auth.SessionPrincipal;
import com.hackatlas.catalog.CatalogDtos;
import com.hackatlas.catalog.CatalogService;
import com.hackatlas.personal.PersonalDtos;
import com.hackatlas.personal.PersonalListService;
import com.hackatlas.personal.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class PersonalController {
    private final ProfileService profiles;
    private final PersonalListService lists;
    private final CatalogService catalog;
    private final JsonRequests requests;

    PersonalController(ProfileService profiles, PersonalListService lists, CatalogService catalog, JsonRequests requests) {
        this.profiles = profiles;
        this.lists = lists;
        this.catalog = catalog;
        this.requests = requests;
    }

    @GetMapping
    PersonalDtos.Profile get(@AuthenticationPrincipal SessionPrincipal principal) { return profiles.get(principal.userId()); }

    @PatchMapping
    PersonalDtos.Profile patch(@AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        return profiles.patch(principal.userId(), requests.patch(request));
    }

    @GetMapping("/saved")
    CatalogDtos.HackathonPage saved(@AuthenticationPrincipal SessionPrincipal principal,
            @RequestParam MultiValueMap<String, String> parameters) {
        var page = PersonalQueryParser.parse(parameters);
        return catalog.saved(principal.userId(), page.page(), page.size());
    }

    @PutMapping("/saved/{id}")
    ResponseEntity<Void> save(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id) {
        lists.save(principal.userId(), CatalogQueryParser.id(id));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/saved/{id}")
    ResponseEntity<Void> unsave(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id) {
        lists.unsave(principal.userId(), CatalogQueryParser.id(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/applications")
    PersonalDtos.ApplicationPage applications(@AuthenticationPrincipal SessionPrincipal principal,
            @RequestParam MultiValueMap<String, String> parameters) {
        var page = PersonalQueryParser.parse(parameters);
        return lists.applications(principal.userId(), page.page(), page.size());
    }

    @PutMapping("/applications/{id}")
    PersonalDtos.ApplicationMark apply(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id) {
        return lists.apply(principal.userId(), CatalogQueryParser.id(id));
    }

    @DeleteMapping("/applications/{id}")
    ResponseEntity<Void> unapply(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id) {
        lists.unapply(principal.userId(), CatalogQueryParser.id(id));
        return ResponseEntity.noContent().build();
    }
}
