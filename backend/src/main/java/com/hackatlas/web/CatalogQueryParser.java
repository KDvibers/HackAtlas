package com.hackatlas.web;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.hackatlas.catalog.CatalogFilter;
import org.springframework.util.MultiValueMap;

final class CatalogQueryParser {
    private static final Set<String> PARAMETERS = Set.of(
            "query", "format", "countryCode", "city", "topic", "registrationStatus",
            "individualApplicationAllowed", "startsFrom", "startsTo", "sort", "page", "size"
    );
    private static final Set<String> FORMATS = Set.of("ONLINE", "OFFLINE", "HYBRID");
    private static final Set<String> REGISTRATION = Set.of("OPEN", "CLOSED", "UPCOMING", "UNKNOWN");
    private static final Set<String> SORTS = Set.of("DEADLINE_ASC", "START_ASC", "NEWEST");

    private CatalogQueryParser() {
    }

    static CatalogFilter parse(MultiValueMap<String, String> parameters) {
        var errors = new ArrayList<ApiProblem.FieldError>();
        parameters.forEach((name, values) -> {
            if (!PARAMETERS.contains(name)) {
                errors.add(new ApiProblem.FieldError(name, "Неизвестный параметр."));
            } else if (values.size() != 1) {
                errors.add(new ApiProblem.FieldError(name, "Параметр допускается только один раз."));
            }
        });
        String query = text(parameters, "query", 120, errors);
        String city = text(parameters, "city", 100, errors);
        String topic = text(parameters, "topic", 50, errors);
        String format = choice(parameters, "format", FORMATS, null, errors);
        String registration = choice(parameters, "registrationStatus", REGISTRATION, null, errors);
        String sort = choice(parameters, "sort", SORTS, "DEADLINE_ASC", errors);
        String country = parameters.getFirst("countryCode");
        if (country != null && !country.matches("[A-Z]{2}")) {
            errors.add(new ApiProblem.FieldError("countryCode", "Нужен код из двух заглавных латинских букв."));
        }
        Boolean individual = null;
        String rawIndividual = parameters.getFirst("individualApplicationAllowed");
        if (rawIndividual != null) {
            if (rawIndividual.equals("true") || rawIndividual.equals("false")) {
                individual = Boolean.valueOf(rawIndividual);
            } else {
                errors.add(new ApiProblem.FieldError("individualApplicationAllowed", "Допустимы true и false."));
            }
        }
        LocalDate from = date(parameters, "startsFrom", errors);
        LocalDate to = date(parameters, "startsTo", errors);
        if (from != null && to != null && from.isAfter(to)) {
            errors.add(new ApiProblem.FieldError("startsTo", "Конечная дата должна быть не раньше начальной."));
        }
        BigInteger page = integer(parameters, "page", BigInteger.ZERO, errors);
        BigInteger size = integer(parameters, "size", BigInteger.valueOf(20), errors);
        if (page.signum() < 0) {
            errors.add(new ApiProblem.FieldError("page", "Номер страницы должен быть неотрицательным."));
        }
        if (size.compareTo(BigInteger.ONE) < 0 || size.compareTo(BigInteger.valueOf(50)) > 0) {
            errors.add(new ApiProblem.FieldError("size", "Размер страницы должен быть от 1 до 50."));
        }
        if (!errors.isEmpty()) {
            throw new CatalogValidationException(errors);
        }
        return new CatalogFilter(
                query == null || query.isBlank() ? null : query.strip(),
                format, country, city, topic, registration, individual, from, to, sort, page, size.intValueExact()
        );
    }

    static UUID id(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new CatalogValidationException(List.of(new ApiProblem.FieldError("id", "Нужен UUID события.")));
        }
        return UUID.fromString(value);
    }

    private static String text(
            MultiValueMap<String, String> parameters, String name, int maxLength, List<ApiProblem.FieldError> errors
    ) {
        String value = parameters.getFirst(name);
        if (value != null && value.codePointCount(0, value.length()) > maxLength) {
            errors.add(new ApiProblem.FieldError(name, "Превышена максимальная длина: " + maxLength + "."));
        }
        return value;
    }

    private static String choice(
            MultiValueMap<String, String> parameters, String name, Set<String> allowed,
            String defaultValue, List<ApiProblem.FieldError> errors
    ) {
        String value = parameters.getFirst(name);
        if (value == null) {
            return defaultValue;
        }
        if (!allowed.contains(value)) {
            errors.add(new ApiProblem.FieldError(name, "Недопустимое значение."));
        }
        return value;
    }

    private static BigInteger integer(
            MultiValueMap<String, String> parameters, String name, BigInteger defaultValue,
            List<ApiProblem.FieldError> errors
    ) {
        String value = parameters.getFirst(name);
        if (value == null) {
            return defaultValue;
        }
        if (!value.matches("[+-]?[0-9]+")) {
            errors.add(new ApiProblem.FieldError(name, "Нужно целое число."));
            return defaultValue;
        }
        return new BigInteger(value);
    }

    private static LocalDate date(
            MultiValueMap<String, String> parameters, String name, List<ApiProblem.FieldError> errors
    ) {
        String value = parameters.getFirst(name);
        if (value == null) {
            return null;
        }
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000-")) {
                throw new DateTimeParseException("Invalid date", value, 0);
            }
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            errors.add(new ApiProblem.FieldError(name, "Нужна существующая дата в формате YYYY-MM-DD."));
            return null;
        }
    }
}
