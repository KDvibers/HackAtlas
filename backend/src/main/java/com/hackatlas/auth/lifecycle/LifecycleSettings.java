package com.hackatlas.auth.lifecycle;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** N2 is explicitly local/test-only. Disabled legacy startup does not need SMTP or a key. */
@Component
public final class LifecycleSettings {
    private final boolean enabled;
    private final byte[] key;
    private final String smtpHost;
    private final int smtpPort;
    private final int queueCapacity;
    private final int requestIpLimit;
    private final int requestEmailLimit;
    private final int verifyIpLimit;
    private final int maxKeys;

    public LifecycleSettings(Environment env) {
        enabled = env.getProperty("hackatlas.lifecycle.enabled", Boolean.class, false);
        smtpHost = env.getProperty("hackatlas.lifecycle.smtp-host", "127.0.0.1");
        smtpPort = positive(env, "smtp-port", 1025);
        queueCapacity = positive(env, "queue-capacity", 16);
        requestIpLimit = positive(env, "request-ip-limit", 10);
        requestEmailLimit = positive(env, "request-email-limit", 5);
        verifyIpLimit = positive(env, "verify-ip-limit", 30);
        maxKeys = positive(env, "max-rate-keys", 10000);
        if (!enabled) { key = null; return; }
        if (!env.acceptsProfiles(Profiles.of("lifecycle-test & (dev | test) & !prod & !release"))) {
            throw new IllegalStateException("Lifecycle requires the isolated dev/test lifecycle-test profile");
        }
        if (!loopback(env.getProperty("server.address", "127.0.0.1")) || !loopback(smtpHost) || smtpPort > 65535) {
            throw new IllegalStateException("Lifecycle test bind and SMTP must use loopback only");
        }
        try {
            String configured = env.getProperty("hackatlas.lifecycle.key-file");
            if (configured == null) throw new IllegalStateException("Lifecycle test key is required");
            Path file = Path.of(configured);
            if (!file.isAbsolute() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Lifecycle test key must be an explicit regular file");
            }
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                var permissions = Files.getPosixFilePermissions(file);
                if (permissions.stream().anyMatch(Set.of(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)::contains)) {
                    throw new IllegalStateException("Lifecycle test key must be owner-private");
                }
            }
            try (var input = Files.newInputStream(file)) { key = input.readNBytes(33); }
            if (key.length != 32) throw new IllegalStateException("Lifecycle test key must contain exactly 32 bytes");
        } catch (java.io.IOException | java.nio.file.InvalidPathException exception) {
            throw new IllegalStateException("Lifecycle test key is unavailable");
        }
    }

    private static int positive(Environment env, String property, int fallback) {
        int value = env.getProperty("hackatlas.lifecycle." + property, Integer.class, fallback);
        if (value < 1) throw new IllegalStateException("Lifecycle limits must be positive");
        return value;
    }

    private static boolean loopback(String host) {
        return Set.of("127.0.0.1", "::1", "localhost").contains(host);
    }

    public boolean enabled() { return enabled; }
    public byte[] hmacKey() { return key == null ? null : key.clone(); }
    public String smtpHost() { return smtpHost; }
    public int smtpPort() { return smtpPort; }
    public int queueCapacity() { return queueCapacity; }
    public int requestIpLimit() { return requestIpLimit; }
    public int requestEmailLimit() { return requestEmailLimit; }
    public int verifyIpLimit() { return verifyIpLimit; }
    public int maxKeys() { return maxKeys; }
}
