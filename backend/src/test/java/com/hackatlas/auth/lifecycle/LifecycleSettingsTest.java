package com.hackatlas.auth.lifecycle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class LifecycleSettingsTest {
    @TempDir Path directory;

    @Test
    void ordinaryProductionStartsDisabledWithoutAnyMailOrKeyConfiguration() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThat(new LifecycleSettings(env).enabled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "release", "test", "lifecycle-test"})
    void enablingRequiresBothDedicatedAndDevOrTestProfilesAndNeverProdOrRelease(String profile) {
        var env = enabled();
        if (profile.equals("prod") || profile.equals("release")) env.setActiveProfiles("test", "lifecycle-test", profile);
        else env.setActiveProfiles(profile);
        assertThatIllegalStateException().isThrownBy(() -> new LifecycleSettings(env));
    }

    @Test
    void closedProfileLoadsOnlyAnExplicitPrivate32ByteKeyAndReturnsDefensiveCopies() throws Exception {
        var env = configured();
        var settings = new LifecycleSettings(env);
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.hmacKey()).hasSize(32);
        var copy = settings.hmacKey();
        copy[0] = 9;
        assertThat(settings.hmacKey()[0]).isZero();
    }

    @Test
    void missingOrWrongLengthKeyNeverFallsBackToGeneratedOrHardcodedSecrets() throws Exception {
        assertThatIllegalStateException().isThrownBy(() -> new LifecycleSettings(enabled()));
        var env = configured();
        Files.write(directory.resolve("hmac.bin"), new byte[31]);
        assertThatIllegalStateException().isThrownBy(() -> new LifecycleSettings(env));
        Files.write(directory.resolve("hmac.bin"), new byte[33]);
        assertThatIllegalStateException().isThrownBy(() -> new LifecycleSettings(env));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "smtp.example.com"})
    void closedModeRejectsExternalBindOrSmtpDestinations(String host) throws Exception {
        var bind = configured().withProperty("server.address", host);
        assertThatIllegalStateException().isThrownBy(() -> new LifecycleSettings(bind));
        var smtp = configured().withProperty("hackatlas.lifecycle.smtp-host", host);
        assertThatIllegalStateException().isThrownBy(() -> new LifecycleSettings(smtp));
    }

    private MockEnvironment configured() throws Exception {
        Path file = directory.resolve("hmac.bin");
        Files.write(file, new byte[32]); // Synthetic fixture only, not a configuration default.
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        return enabled().withProperty("hackatlas.lifecycle.key-file", file.toString());
    }

    private MockEnvironment enabled() {
        var env = new MockEnvironment().withProperty("hackatlas.lifecycle.enabled", "true");
        env.setActiveProfiles("test", "lifecycle-test");
        return env;
    }
}
