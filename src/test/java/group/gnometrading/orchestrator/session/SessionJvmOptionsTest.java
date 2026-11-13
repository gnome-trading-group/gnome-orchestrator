package group.gnometrading.orchestrator.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionJvmOptionsTest {

    @Test
    void shipsOneOptionPerLineForBothLaunchPaths() throws IOException {
        List<String> options;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("session-jvm-options")) {
            assertNotNull(
                    in, "session-jvm-options must be at the jar root, where start-session.sh and gnomepy read it");
            options = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
        assertFalse(options.isEmpty());
        for (String option : options) {
            assertTrue(option.startsWith("-") && !option.contains(" "), "not a single JVM option: " + option);
        }
    }
}
