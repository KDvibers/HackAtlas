package com.hackatlas.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

import jakarta.persistence.Embeddable;

@Embeddable
class CatalogEventTime {
    String precision;
    LocalDate date;
    LocalTime time;
    String timeZone;
    Instant instant;

    protected CatalogEventTime() {
    }

    static CatalogEventTime from(CatalogDtos.EventTime value) {
        if (value == null) {
            return null;
        }
        Objects.requireNonNull(value.date(), "EventTime date");
        ZoneId zone = null;
        if (value.timeZone() != null) {
            if (!ZoneId.getAvailableZoneIds().contains(value.timeZone())) {
                throw new IllegalArgumentException("EventTime requires an IANA time zone");
            }
            zone = ZoneId.of(value.timeZone());
        }
        switch (value.precision()) {
            case "DATE" -> {
                if (value.time() != null || value.instant() != null) {
                    throw new IllegalArgumentException("DATE has no time or instant");
                }
            }
            case "MINUTE" -> {
                if (value.time() == null || value.instant() == null || zone == null
                        || value.time().getSecond() != 0 || value.time().getNano() != 0) {
                    throw new IllegalArgumentException("MINUTE requires a minute, IANA zone and instant");
                }
                var zoned = value.instant().atZone(zone);
                if (!zoned.toLocalDate().equals(value.date()) || !zoned.toLocalTime().equals(value.time())) {
                    throw new IllegalArgumentException("EventTime date/time/zone/instant disagree");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported EventTime precision");
        }
        var result = new CatalogEventTime();
        result.precision = value.precision();
        result.date = value.date();
        result.time = value.time();
        result.timeZone = value.timeZone();
        result.instant = value.instant();
        return result;
    }

    CatalogDtos.EventTime toDto() {
        return new CatalogDtos.EventTime(precision, date, time, timeZone, instant);
    }
}
