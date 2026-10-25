package com.hackatlas.auth;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordConfigurationTest {
    private final PasswordConfiguration configuration = new PasswordConfiguration();

    @Test
    void argon2idUsesIndependentSaltsAndPreservesWhitespaceAndLongUnicodePasswords() {
        var encoder = configuration.passwordEncoder(19456, 2, 4);
        String password = " " + "🔐".repeat(254) + " ";
        String first = encoder.encode(password);
        String second = encoder.encode(password);
        assertThat(first).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(first).isNotEqualTo(second).doesNotContain(password);
        assertThat(encoder.matches(password, first)).isTrue();
        assertThat(encoder.matches(password.strip(), first)).isFalse();
        String changedLastCharacter = password.substring(0, password.offsetByCodePoints(0, 254)) + "x ";
        assertThat(encoder.matches(changedLastCharacter, first)).isFalse();
    }

    @Test
    void refusesWeakerThanDocumentedMinimumParameters() {
        assertThatThrownBy(() -> configuration.passwordEncoder(16384, 2, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration.passwordEncoder(19456, 1, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration.passwordEncoder(19456, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuedTokenIsNotExposedByRecordToString() {
        assertThat(new IssuedSession("secret-test-token", Instant.EPOCH, UUID.randomUUID()).toString())
                .doesNotContain("secret-test-token").contains("REDACTED");
    }
}
