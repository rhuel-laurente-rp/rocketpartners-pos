package com.rocketpartners.onboarding.posdiscountengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Phase 3 discount engine — a Spring Boot REST API that owns the discount
 * rules in a database and (in a later branch) evaluates them against a transaction.
 *
 * <p><strong>This class lives in {@code posdiscountengine} on purpose.</strong> A
 * {@link SpringBootApplication} component-scans <em>downward</em> from its own package, so keeping
 * the entry point here guarantees the scan never reaches the Swing packages
 * ({@code possystem}, {@code posvirtualjournal}) that share this single project. Moving it up to
 * {@code com.rocketpartners.onboarding} would drag those packages into the Spring context and
 * break both other entry points in confusing ways. See {@code CLAUDE.md}, Phase 3.</p>
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
