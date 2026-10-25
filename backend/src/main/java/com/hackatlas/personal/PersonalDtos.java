package com.hackatlas.personal;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class PersonalDtos {
    private PersonalDtos() { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Profile(UUID id, String email, String displayName, String city, String countryCode,
            String roleLabel, String avatarKey, List<String> interests, Stats stats) { }

    public record Stats(long savedCount, long appliedCount, long interestsCount) { }

    public record ApplicationMark(UUID hackathonId, String status, boolean selfReported, Instant markedAt) { }

    public record ApplicationPage(List<ApplicationMark> items, BigInteger page, int size,
            long totalElements, long totalPages, boolean hasNext, Instant asOf) { }
}
