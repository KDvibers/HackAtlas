package com.hackatlas.catalog;

import java.math.BigInteger;
import java.time.LocalDate;

/** Validated public query parameters. SQL fragments never come from this record. */
public record CatalogFilter(
        String query,
        String format,
        String countryCode,
        String city,
        String topic,
        String registrationStatus,
        Boolean individualApplicationAllowed,
        LocalDate startsFrom,
        LocalDate startsTo,
        String sort,
        BigInteger page,
        int size
) {
}
