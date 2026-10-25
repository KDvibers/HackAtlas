package com.hackatlas.admin;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.hackatlas.moderation.SafeHttpsUrl;
import com.hackatlas.web.ApiFailure;
import org.springframework.util.MultiValueMap;

/** Explicit browser form allowlist. No entity binding or publication/privilege mass assignment. */
public record AdminEventForm(Map<String, String> values, EventTime starts, EventTime ends, EventTime deadline) {
    public static final Set<String> FIELDS = Set.of("title", "shortDescription", "description", "organizerName",
            "organizerWebsiteUrl", "format", "countryCode", "city", "venue", "officialUrl", "registrationUrl",
            "rulesUrl", "sourceUrl", "registrationStatus", "eventStatus", "startsPrecision", "startsDate",
            "startsTime", "startsTimeZone", "endsPrecision", "endsDate", "endsTime", "endsTimeZone",
            "deadlinePrecision", "deadlineDate", "deadlineTime", "deadlineTimeZone");

    public record EventTime(String precision, LocalDate date, LocalTime time, String timeZone, Instant instant) { }

    public static AdminEventForm parse(MultiValueMap<String, String> parameters) {
        parameters.forEach((key, entries) -> {
            if ((!FIELDS.contains(key) && !key.equals("_csrf")) || entries.size() != 1) {
                throw invalid("Неизвестное или повторяющееся поле формы.");
            }
        });
        var data = defaults();
        for (String field : FIELDS) { data.put(field, parameters.getFirst(field) == null ? "" : parameters.getFirst(field)); }
        text(data, "title", 1, 200);
        text(data, "shortDescription", 0, 350);
        text(data, "description", 0, 20000);
        text(data, "organizerName", 1, 160);
        text(data, "city", 0, 100);
        text(data, "venue", 0, 300);
        text(data, "countryCode", 0, 2);
        if (!data.get("countryCode").isEmpty() && !data.get("countryCode").matches("[A-Z]{2}")) {
            throw invalid("Код страны должен состоять из двух заглавных латинских букв.");
        }
        oneOf(data, "format", Set.of("ONLINE", "OFFLINE", "HYBRID"));
        oneOf(data, "registrationStatus", Set.of("OPEN", "CLOSED", "UPCOMING", "UNKNOWN"));
        oneOf(data, "eventStatus", Set.of("SCHEDULED", "ONGOING", "ENDED", "POSTPONED", "CANCELLED"));
        for (String field : Set.of("organizerWebsiteUrl", "officialUrl", "registrationUrl", "rulesUrl", "sourceUrl")) {
            String value = data.get(field).strip();
            data.put(field, value.isEmpty() ? "" : SafeHttpsUrl.validate(value, field));
        }
        var starts = time(data, "starts");
        var ends = time(data, "ends");
        var deadline = time(data, "deadline");
        if (starts != null && ends != null && (starts.instant != null && ends.instant != null
                ? ends.instant.isBefore(starts.instant) : ends.date.isBefore(starts.date))) {
            throw invalid("Окончание события не может быть раньше начала.");
        }
        return new AdminEventForm(Map.copyOf(data), starts, ends, deadline);
    }

    public static LinkedHashMap<String, String> defaults() {
        var values = new LinkedHashMap<String, String>();
        FIELDS.forEach(field -> values.put(field, ""));
        values.put("format", "ONLINE");
        values.put("registrationStatus", "UNKNOWN");
        values.put("eventStatus", "SCHEDULED");
        return values;
    }

    public String value(String field) { return values.get(field); }
    public String nullable(String field) { return value(field).isEmpty() ? null : value(field); }

    private static EventTime time(Map<String, String> data, String prefix) {
        String precision = data.get(prefix + "Precision");
        String date = data.get(prefix + "Date");
        String time = data.get(prefix + "Time");
        String zone = data.get(prefix + "TimeZone");
        if (precision.isEmpty()) {
            if (!date.isEmpty() || !time.isEmpty() || !zone.isEmpty()) {
                throw invalid("При неизвестной дате очистите дату, время и часовой пояс: " + prefix);
            }
            return null;
        }
        try {
            if (!Set.of("DATE", "MINUTE").contains(precision) || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw invalid("Проверьте точность и дату: " + prefix);
            }
            LocalDate localDate = LocalDate.parse(date);
            if (localDate.getYear() < 1 || (!zone.isEmpty() && !ZoneId.getAvailableZoneIds().contains(zone))) {
                throw invalid("Нужна действительная дата и часовой пояс IANA: " + prefix);
            }
            if (precision.equals("DATE")) {
                if (!time.isEmpty()) { throw invalid("Для точности DATE время должно быть пустым: " + prefix); }
                return new EventTime("DATE", localDate, null, zone.isEmpty() ? null : zone, null);
            }
            if (!time.matches("[0-9]{2}:[0-9]{2}") || zone.isEmpty()) {
                throw invalid("Для MINUTE нужны точное время и часовой пояс: " + prefix);
            }
            LocalTime localTime = LocalTime.parse(time);
            LocalDateTime dateTime = LocalDateTime.of(localDate, localTime);
            var offsets = ZoneId.of(zone).getRules().getValidOffsets(dateTime);
            if (offsets.size() != 1) {
                throw invalid("Время попало в переход часового пояса. Уточните однозначное время у источника: " + prefix);
            }
            return new EventTime("MINUTE", localDate, localTime, zone, dateTime.toInstant(offsets.getFirst()));
        } catch (DateTimeException exception) {
            throw invalid("Некорректная дата, время или часовой пояс: " + prefix);
        }
    }

    private static void text(Map<String, String> values, String key, int min, int max) {
        String value = values.get(key);
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max || value.indexOf('\0') >= 0 || (min > 0 && value.isBlank())) {
            throw invalid("Проверьте длину поля: " + key);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) { throw invalid("Некорректный текст."); }
            } else if (Character.isLowSurrogate(c)) { throw invalid("Некорректный текст."); }
        }
    }

    private static void oneOf(Map<String, String> values, String field, Set<String> allowed) {
        if (!allowed.contains(values.get(field))) { throw invalid("Некорректное значение: " + field); }
    }

    private static ApiFailure invalid(String message) { return new ApiFailure(400, "VALIDATION_ERROR", message); }
}
