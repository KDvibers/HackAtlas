package com.hackatlas.catalog;

/**
 * One status expression for list, detail and metadata, evaluated against a single
 * injected Clock instant. Explicit CANCELLED/POSTPONED/ENDED states win.
 * MINUTE boundaries are inclusive; DATE endings/deadlines expire only after the
 * local date, and DATE starts become ongoing on that local date. Without a known
 * zone a DATE cannot trigger an automatic transition. Postponed dates are stale:
 * they neither advance the event nor automatically close its registration.
 */
final class CatalogStatusPolicy {
    private CatalogStatusPolicy() {
    }

    static final String CTE = """
            WITH catalog_event_status AS (
                SELECT h.*,
                    CASE
                        WHEN h.event_status IN ('CANCELLED', 'POSTPONED', 'ENDED') THEN h.event_status
                        WHEN %s THEN 'ENDED'
                        WHEN h.event_status = 'ONGOING' OR %s THEN 'ONGOING'
                        ELSE 'SCHEDULED'
                    END AS effective_event_status
                FROM hackatlas.catalog_hackathon h
            ), effective_catalog AS (
                SELECT h.*,
                    CASE
                        WHEN h.effective_event_status IN ('CANCELLED', 'ENDED') THEN 'CLOSED'
                        WHEN h.registration_status = 'CLOSED' THEN 'CLOSED'
                        WHEN h.effective_event_status <> 'POSTPONED' AND (%s) THEN 'CLOSED'
                        ELSE h.registration_status
                    END AS effective_registration_status
                FROM catalog_event_status h
            )
            """.formatted(boundary("ends", false), boundary("starts", true), boundary("deadline", false));

    static final String ACTIVE = """
            h.publication_status = 'PUBLISHED'
            AND h.effective_event_status NOT IN ('ENDED', 'CANCELLED')
            """;

    private static String boundary(String prefix, boolean starts) {
        // Prefix/comparison are internal constants, never query parameter values.
        return """
                (
                    (h.%1$s_precision = 'MINUTE' AND h.%1$s_instant <= CAST(:asOf AS timestamptz))
                    OR
                    (h.%1$s_precision = 'DATE' AND h.%1$s_time_zone IS NOT NULL
                        AND CAST(timezone(h.%1$s_time_zone, CAST(:asOf AS timestamptz)) AS date)
                            %2$s h.%1$s_date)
                )
                """.formatted(prefix, starts ? ">=" : ">");
    }
}
