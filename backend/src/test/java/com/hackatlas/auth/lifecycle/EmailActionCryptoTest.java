package com.hackatlas.auth.lifecycle;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static com.hackatlas.auth.lifecycle.EmailActionPurpose.EMAIL_VERIFICATION;
import static com.hackatlas.auth.lifecycle.EmailActionPurpose.PASSWORD_RESET;

class EmailActionCryptoTest {
    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String ID = "A".repeat(43);
    private static final String CODE = "01234567";
    // Independently calculated with Python stdlib hmac/hashlib, not production helpers.
    private static final String MAC = "7408c5c2d91f8f28751719fdef9ec63cdfd23b416e663c065e45559be7985de8";

    @Test
    void bindsLeadingZeroCodeToAnIndependentHmacVectorAndHashesOnlyOpaqueIdentifiers() {
        var crypto = new EmailActionCrypto(key());
        assertThat(crypto.codeMac(EMAIL_VERIFICATION, USER, 7, ID, CODE)).isEqualTo(MAC);
        assertThat(crypto.matchesCode(MAC, EMAIL_VERIFICATION, USER, 7, ID, CODE)).isTrue();
        assertThat(crypto.hashToken(ID)).isEqualTo("0f007385b6f9d4b7eeb2748605afe1a984a0a3bfa3f014d09e2a784ce9e5cd1a");
    }

    @Test
    void anotherPurposeAccountVersionChallengeCodeOrKeyCannotUseTheSameProof() {
        var crypto = new EmailActionCrypto(key());
        assertThat(crypto.matchesCode(MAC, PASSWORD_RESET, USER, 7, ID, CODE)).isFalse();
        assertThat(crypto.matchesCode(MAC, EMAIL_VERIFICATION, new UUID(0, 1), 7, ID, CODE)).isFalse();
        assertThat(crypto.matchesCode(MAC, EMAIL_VERIFICATION, USER, 8, ID, CODE)).isFalse();
        assertThat(crypto.matchesCode(MAC, EMAIL_VERIFICATION, USER, 7, "B".repeat(43), CODE)).isFalse();
        assertThat(crypto.matchesCode(MAC, EMAIL_VERIFICATION, USER, 7, ID, "01234568")).isFalse();
        var rotated = key();
        rotated[0] = 99;
        assertThat(new EmailActionCrypto(rotated).matchesCode(MAC, EMAIL_VERIFICATION, USER, 7, ID, CODE)).isFalse();
        assertThat(crypto.matchesCode("bad persisted value", EMAIL_VERIFICATION, USER, 7, ID, CODE)).isFalse();
    }

    @Test
    void createsIndependentShapeLimitedSecretsWithMicrosecondDeadlinesAndRedactedDiagnostics() {
        var crypto = new EmailActionCrypto(key());
        var now = Instant.parse("2026-11-03T10:00:00.123456789Z");
        var challenge = crypto.newChallenge(now);
        var other = crypto.newChallenge(now);
        var grant = crypto.newResetGrant(now);
        assertThat(challenge.challengeId()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(other.challengeId());
        assertThat(challenge.code()).matches("[0-9]{8}");
        assertThat(grant.resetToken()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(challenge.challengeId());
        assertThat(challenge.createdAt()).isEqualTo(Instant.parse("2026-11-03T10:00:00.123456Z"));
        assertThat(challenge.expiresAt()).isEqualTo(Instant.parse("2026-11-03T10:10:00.123456Z"));
        assertThat(grant.expiresAt()).isEqualTo(Instant.parse("2026-11-03T10:05:00.123456Z"));
        assertThat(challenge.toString()).doesNotContain(challenge.challengeId(), challenge.code());
        assertThat(grant.toString()).doesNotContain(grant.resetToken());
        var withLeadingZero = new EmailActionCrypto.Challenge(ID, CODE, challenge.createdAt(), challenge.expiresAt());
        assertThat(withLeadingZero.code()).isEqualTo("01234567");
    }

    @ParameterizedTest
    @MethodSource("invalidCodes")
    void refusesMalformedCodeWithoutEchoingTheSecret(String code) {
        var crypto = new EmailActionCrypto(key());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> crypto.codeMac(EMAIL_VERIFICATION, USER, 7, ID, code))
                .withMessage("Invalid email action input");
        assertThat(crypto.matchesCode(MAC, EMAIL_VERIFICATION, USER, 7, ID, code)).isFalse();
    }

    static Stream<String> invalidCodes() {
        return Stream.of(null, "1234567", "123456789", "１２３４５６７８", "1234 678", "1234567\n");
    }

    @Test
    void requiresASeparate256BitKeyAndDefensivelyCopiesItsInput() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EmailActionCrypto(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new EmailActionCrypto(new byte[31]));
        assertThatIllegalArgumentException().isThrownBy(() -> new EmailActionCrypto(new byte[33]));
        var input = key();
        var crypto = new EmailActionCrypto(input);
        Arrays.fill(input, (byte) 0);
        assertThat(crypto.codeMac(EMAIL_VERIFICATION, USER, 7, ID, CODE)).isEqualTo(MAC);
        assertThatIllegalArgumentException().isThrownBy(() -> crypto.hashToken("a short secret"))
                .withMessage("Invalid email action input");
        assertThatIllegalArgumentException().isThrownBy(() -> crypto.codeMac(EMAIL_VERIFICATION, USER, -1, ID, CODE));
    }

    private static byte[] key() {
        var key = new byte[32];
        for (int index = 0; index < key.length; index++) key[index] = (byte) index;
        return key;
    }
}
