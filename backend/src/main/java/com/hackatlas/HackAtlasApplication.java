package com.hackatlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HackAtlasApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(HackAtlasApplication.class, args);
        if (context.getEnvironment().getProperty("hackatlas.release.migration-only", Boolean.class, false)) {
            // Flyway completes during startup. Closing a successful non-web run releases the pool
            // and lets the one-off migration process exit normally; startup failures remain nonzero.
            if (SpringApplication.exit(context) != 0) {
                throw new IllegalStateException("Migration process did not close successfully");
            }
        }
    }
}
