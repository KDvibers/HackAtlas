package com.hackatlas.auth.lifecycle;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Internal foundation only: no bean, deployment key, HTTP endpoint or mail sender is wired yet. */
public final class EmailActionCrypto {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern CODE = Pattern.compile("[0-9]{8}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public EmailActionCrypto(byte[] hmacKey) {
        if (hmacKey == null || hmacKey.length != 32) {
            throw new IllegalArgumentException("Email action HMAC requires a separate 32-byte key");
        }
        key = new SecretKeySpec(hmacKey.clone(), "HmacSHA256");
    }

    public Challenge newChallenge(Instant now) {
        Instant created = now.truncatedTo(ChronoUnit.MICROS);
        String code = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
        return new Challenge(newToken(), code, created, created.plus(Duration.ofMinutes(10)));
    }

    public ResetGrant newResetGrant(Instant now) {
        Instant created = now.truncatedTo(ChronoUnit.MICROS);
        return new ResetGrant(newToken(), created, created.plus(Duration.ofMinutes(5)));
    }

    public String hashToken(String token) {
        require(validToken(token));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Required Java SHA-256 algorithm unavailable", exception);
        }
    }

    public String codeMac(EmailActionPurpose purpose, UUID userId, long credentialVersion,
                          String challengeId, String code) {
        require(purpose != null && userId != null && credentialVersion >= 0 && validToken(challengeId) && validCode(code));
        // Fixed domain + NUL-separated, validated ASCII fields make the binding unambiguous.
        String input = String.join("\0", "hackatlas.email-action.v1", purpose.name(), userId.toString(),
                Long.toString(credentialVersion), challengeId, code);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Required Java HmacSHA256 algorithm unavailable", exception);
        }
    }

    public boolean matchesCode(String expectedMac, EmailActionPurpose purpose, UUID userId,
                               long credentialVersion, String challengeId, String code) {
        if (expectedMac == null || !HASH.matcher(expectedMac).matches() || purpose == null || userId == null
                || credentialVersion < 0 || !validToken(challengeId) || !validCode(code)) return false;
        return MessageDigest.isEqual(HexFormat.of().parseHex(expectedMac),
                HexFormat.of().parseHex(codeMac(purpose, userId, credentialVersion, challengeId, code)));
    }

    static boolean validToken(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    static boolean validCode(String value) {
        return value != null && CODE.matcher(value).matches();
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid email action input");
    }

    private static void lifetime(Instant created, Instant expires, Duration expected) {
        require(created != null && expires != null && created.getNano() % 1000 == 0
                && expires.equals(created.plus(expected)));
    }

    public record Challenge(String challengeId, String code, Instant createdAt, Instant expiresAt) {
        public Challenge {
            require(validToken(challengeId) && validCode(code));
            lifetime(createdAt, expiresAt, Duration.ofMinutes(10));
        }

        @Override public String toString() { return "EmailActionChallenge[REDACTED]"; }
    }

    public record ResetGrant(String resetToken, Instant createdAt, Instant expiresAt) {
        public ResetGrant {
            require(validToken(resetToken));
            lifetime(createdAt, expiresAt, Duration.ofMinutes(5));
        }

        @Override public String toString() { return "PasswordResetGrant[REDACTED]"; }
    }
}
