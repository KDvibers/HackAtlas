package com.hackatlas.personal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A missing map key is different from a present key with null. */
public record ProfilePatch(Map<String, String> fields, List<String> interests) {
    public ProfilePatch {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        interests = interests == null ? null : List.copyOf(interests);
    }
}
