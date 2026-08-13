package com.rocketpartners.onboarding.possystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The load-bearing Phase-3 invariant: the POS and the discount engine talk only over HTTP. They
 * share a classpath, so nothing mechanically stops a direct call — this test does. An import
 * crossing the boundary in either direction would pass every local test and then fail against the
 * deployed load balancer, and would make the whole containerise-and-deploy exercise meaningless.
 */
class PackageBoundaryTest {

    private static final Path SRC = Path.of("src/main/java/com/rocketpartners/onboarding");
    private static final String POS_PKG = "com.rocketpartners.onboarding.possystem";
    private static final String ENGINE_PKG = "com.rocketpartners.onboarding.posdiscountengine";

    @Test
    void possystemNeverImportsPosdiscountengine_andViceVersa() throws IOException {
        assumeTrue(Files.isDirectory(SRC), "source tree not at expected relative path");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String path = p.toString().replace('\\', '/');
                boolean inPos = path.contains("/possystem/");
                boolean inEngine = path.contains("/posdiscountengine/");
                if (!inPos && !inEngine) return;
                List<String> lines;
                try {
                    lines = Files.readAllLines(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                for (String line : lines) {
                    String t = line.trim();
                    if (!t.startsWith("import ")) continue;
                    if (inPos && t.contains(ENGINE_PKG)) {
                        violations.add(path + " -> " + t);
                    }
                    if (inEngine && t.contains(POS_PKG)) {
                        violations.add(path + " -> " + t);
                    }
                }
            });
        }
        assertThat(violations)
                .as("POS <-> discount-engine must be HTTP-only; no cross-package imports")
                .isEmpty();
    }
}
