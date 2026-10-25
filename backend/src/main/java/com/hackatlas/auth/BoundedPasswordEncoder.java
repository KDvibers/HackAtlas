package com.hackatlas.auth;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import com.hackatlas.web.ApiFailure;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Caps Argon2's concurrent memory/CPU use without holding servlet threads in a queue. */
final class BoundedPasswordEncoder implements PasswordEncoder {
    private final PasswordEncoder delegate;
    private final Semaphore permits;

    BoundedPasswordEncoder(PasswordEncoder delegate, int concurrency) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("Password concurrency must be positive");
        }
        this.delegate = delegate;
        this.permits = new Semaphore(concurrency);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return limited(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return limited(() -> delegate.matches(rawPassword, encodedPassword));
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    private <T> T limited(Supplier<T> operation) {
        if (!permits.tryAcquire()) {
            throw new ApiFailure(429, "RATE_LIMITED", "Сервис входа занят. Повторите через секунду.", 1);
        }
        try {
            return operation.get();
        } finally {
            permits.release();
        }
    }
}
