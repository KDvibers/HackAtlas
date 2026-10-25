package com.hackatlas.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.hackatlas.personal.ProfilePatch;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses bounded JSON without coercion, duplicate fields, mass assignment or password trimming. */
@Component
final class JsonRequests {
    static final int MAX_BYTES = 16 * 1024;
    private static final Set<String> PROFILE_FIELDS = Set.of("displayName", "city", "countryCode", "roleLabel", "avatarKey", "interests");
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Validator validator;

    JsonRequests(Validator validator) { this.validator = validator; }

    record Registration(String email, String password, String displayName) {
        @Override public String toString() { return "Registration[redacted]"; }
    }
    record Login(String email, String password) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    private record EmailValue(@Email String value) { }

    Registration registration(HttpServletRequest request) {
        var body = object(request, Set.of("email", "password", "displayName"));
        return new Registration(email(body), text(body, "password", 12, 256, false, true),
                text(body, "displayName", 1, 60, false, false));
    }

    Login login(HttpServletRequest request) {
        var body = object(request, Set.of("email", "password"));
        return new Login(email(body), text(body, "password", 1, 256, false, true));
    }

    ProfilePatch patch(HttpServletRequest request) {
        var body = object(request, PROFILE_FIELDS);
        if (body.isEmpty()) { throw invalid("body"); }
        var fields = new LinkedHashMap<String, String>();
        for (String field : PROFILE_FIELDS) {
            if (!body.has(field) || field.equals("interests")) { continue; }
            String value = switch (field) {
                case "displayName" -> text(body, field, 1, 60, false, false);
                case "city", "roleLabel" -> text(body, field, 0, 100, true, false);
                case "countryCode" -> text(body, field, 2, 2, true, false);
                case "avatarKey" -> text(body, field, 1, 10, false, false);
                default -> throw new IllegalStateException();
            };
            if (field.equals("countryCode") && value != null && !value.matches("[A-Z]{2}")) { throw invalid(field); }
            if (field.equals("avatarKey") && !Set.of("HOODED", "INITIALS").contains(value)) { throw invalid(field); }
            fields.put(field, value);
        }
        List<String> interests = null;
        if (body.has("interests")) {
            JsonNode node = body.get("interests");
            if (!node.isArray() || node.size() > 20) { throw invalid("interests"); }
            interests = new ArrayList<>();
            var unique = new HashSet<String>();
            for (JsonNode value : node) {
                if (!value.isString()) { throw invalid("interests"); }
                String tag = value.asString();
                checkString(tag, "interests", 1, 50, false);
                if (!unique.add(tag)) { throw invalid("interests"); }
                interests.add(tag);
            }
        }
        return new ProfilePatch(fields, interests);
    }

    private String email(JsonNode body) {
        String email = text(body, "email", 1, 254, false, false).strip().toLowerCase(Locale.ROOT);
        // Unicode case conversion may expand characters; validate the value actually persisted.
        checkString(email, "email", 1, 254, false);
        if (email.isEmpty() || !validator.validate(new EmailValue(email)).isEmpty()) { throw invalid("email"); }
        return email;
    }

    private JsonNode object(HttpServletRequest request, Set<String> allowed) {
        try {
            String type = request.getContentType();
            if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(type))) {
                throw invalid("body");
            }
            if (request.getContentLengthLong() > MAX_BYTES) { throw invalid("body"); }
            byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) { throw invalid("body"); }
            JsonNode body = mapper.readTree(bytes);
            if (body == null || !body.isObject()) { throw invalid("body"); }
            for (String field : body.propertyNames()) {
                if (!allowed.contains(field)) { throw invalid("body"); }
            }
            return body;
        } catch (CatalogValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            // Parser exception messages can contain passwords or JSON source; never forward/log them.
            throw invalid("body");
        }
    }

    private static String text(JsonNode body, String field, int min, int max, boolean nullable, boolean password) {
        var node = body.get(field);
        if (node == null) { throw invalid(field); }
        if (node.isNull() && nullable) { return null; }
        if (!node.isString()) { throw invalid(field); }
        String value = node.asString();
        checkString(value, field, min, max, password);
        return value;
    }

    private static void checkString(String value, String field, int min, int max, boolean password) {
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max || (!password && value.indexOf('\0') >= 0)) { throw invalid(field); }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) { throw invalid(field); }
            } else if (Character.isLowSurrogate(c)) { throw invalid(field); }
        }
    }

    private static CatalogValidationException invalid(String field) {
        return new CatalogValidationException(List.of(new ApiProblem.FieldError(field, "Некорректное значение поля.")));
    }
}
