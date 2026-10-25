package com.hackatlas.admin;

import java.util.UUID;
import com.hackatlas.moderation.ModerationService;
import com.hackatlas.web.ApiFailure;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin")
public class AdminController {
    private final AdminCatalogueService catalogue;
    private final ModerationService moderation;

    public AdminController(AdminCatalogueService catalogue, ModerationService moderation) {
        this.catalogue = catalogue; this.moderation = moderation;
    }

    @GetMapping("/login")
    String login() { return "admin/login"; }

    @GetMapping({"", "/"})
    String index(@RequestParam(defaultValue="0") String page, Model model) {
        int number;
        try { number = Integer.parseInt(page); if (number < 0) { throw new NumberFormatException(); } }
        catch (NumberFormatException exception) { throw new ApiFailure(400,"VALIDATION_ERROR","Некорректная страница."); }
        var rows = catalogue.list(number);
        model.addAttribute("events", rows);
        model.addAttribute("page", number);
        model.addAttribute("hasNext", rows.size() == 50 && number < Integer.MAX_VALUE);
        return "admin/index";
    }

    @GetMapping("/events/new")
    String newEvent(Model model) {
        model.addAttribute("form", AdminEventForm.defaults());
        model.addAttribute("action", "/admin/events");
        return "admin/edit";
    }

    @GetMapping("/events/{id}/edit")
    String edit(@PathVariable String id, Model model) {
        var editor = catalogue.editor(id(id));
        model.addAttribute("event", editor.event());
        model.addAttribute("form", editor.form());
        model.addAttribute("action", "/admin/events/" + editor.event().id());
        return "admin/edit";
    }

    @PostMapping("/events")
    String create(@AuthenticationPrincipal AdminPrincipal principal,
                  @RequestParam MultiValueMap<String,String> parameters, Model model, HttpServletResponse response) {
        try {
            UUID id = catalogue.create(principal.userId(), AdminEventForm.parse(parameters));
            return "redirect:/admin/events/" + id + "/edit";
        } catch (ApiFailure failure) {
            if (failure.status() != 400) { throw failure; }
            return invalidForm(parameters, "/admin/events", failure, model, response);
        }
    }

    @PostMapping("/events/{id}")
    String update(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable String id,
                  @RequestParam MultiValueMap<String,String> parameters, Model model, HttpServletResponse response) {
        UUID eventId = id(id);
        try {
            catalogue.edit(principal.userId(), eventId, AdminEventForm.parse(parameters));
            return "redirect:/admin/events/" + eventId + "/edit";
        } catch (ApiFailure failure) {
            if (failure.status() != 400) { throw failure; }
            model.addAttribute("event", catalogue.editor(eventId).event());
            return invalidForm(parameters, "/admin/events/" + eventId, failure, model, response);
        }
    }

    @PostMapping("/events/{id}/publish")
    String publish(@AuthenticationPrincipal AdminPrincipal principal,@PathVariable String id,
                   @RequestParam(defaultValue="false") boolean reviewConfirmed) {
        catalogue.publish(principal.userId(), id(id), reviewConfirmed);
        return "redirect:/admin/events/" + id(id) + "/edit";
    }

    @PostMapping("/events/{id}/archive")
    String archive(@AuthenticationPrincipal AdminPrincipal principal,@PathVariable String id) {
        catalogue.archive(principal.userId(), id(id));
        return "redirect:/admin/events/" + id(id) + "/edit";
    }

    @PostMapping("/events/{id}/verify")
    String verify(@AuthenticationPrincipal AdminPrincipal principal,@PathVariable String id,
                  @RequestParam(defaultValue="false") boolean sourceChecked) {
        catalogue.verify(principal.userId(), id(id), sourceChecked);
        return "redirect:/admin/events/" + id(id) + "/edit";
    }

    @GetMapping("/queue")
    String queue(Model model) {
        model.addAttribute("submissions", moderation.pending());
        return "admin/queue";
    }

    @PostMapping("/queue/{id}/review")
    String review(@AuthenticationPrincipal AdminPrincipal principal,@PathVariable String id,
                  @RequestParam String decision) {
        catalogue.review(principal.userId(),id(id),decision);
        return "redirect:/admin/queue";
    }

    private static String invalidForm(MultiValueMap<String,String> parameters, String action, ApiFailure failure,
                                      Model model, HttpServletResponse response) {
        var form = AdminEventForm.defaults();
        AdminEventForm.FIELDS.forEach(field -> {
            String value = parameters.getFirst(field);
            if (value != null) { form.put(field,value); }
        });
        model.addAttribute("form",form);
        model.addAttribute("action",action);
        model.addAttribute("error",failure.getMessage());
        response.setStatus(400);
        return "admin/edit";
    }

    private static UUID id(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new ApiFailure(400,"VALIDATION_ERROR","Некорректный идентификатор.");
        }
        return UUID.fromString(value);
    }
}
