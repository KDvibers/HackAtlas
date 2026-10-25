package com.hackatlas.web;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.springframework.util.MultiValueMap;

final class PersonalQueryParser {
    private PersonalQueryParser() { }
    record Page(BigInteger page, int size) { }

    static Page parse(MultiValueMap<String, String> values) {
        values.forEach((key, entries) -> {
            if (!Set.of("page", "size").contains(key) || entries.size() != 1) {
                throw invalid("parameters");
            }
        });
        BigInteger page = integer(values.getFirst("page"), BigInteger.ZERO, "page");
        BigInteger size = integer(values.getFirst("size"), BigInteger.valueOf(20), "size");
        if (page.signum() < 0) { throw invalid("page"); }
        if (size.signum() <= 0 || size.compareTo(BigInteger.valueOf(50)) > 0) { throw invalid("size"); }
        return new Page(page, size.intValueExact());
    }

    private static BigInteger integer(String value, BigInteger defaultValue, String field) {
        if (value == null) { return defaultValue; }
        if (!value.matches("[+-]?[0-9]+")) { throw invalid(field); }
        return new BigInteger(value);
    }

    private static CatalogValidationException invalid(String field) {
        return new CatalogValidationException(List.of(new ApiProblem.FieldError(field, "Некорректный параметр страницы.")));
    }
}
