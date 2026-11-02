package dev.villani.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Package dependency rules")
class PackageDependencyTest {

    private static final Path MAIN = Path.of("src/main/java/dev/villani");

    @Test
    @DisplayName("given the reusable server package, when scanning its sources, then it never references the application or benchmark packages")
    void givenServerSources_whenScanningImports_thenNoDependencyOnApplicationOrBenchmark() throws IOException {
        // given
        final Path server = MAIN.resolve("server");
        final List<String> forbidden = List.of("dev.villani.application", "dev.villani.benchmark");

        // when
        final List<String> violations;
        try (Stream<Path> files = Files.walk(server)) {
            violations = files.filter(f -> f.toString().endsWith(".java"))
                    .flatMap(f -> forbidden.stream()
                            .filter(p -> contains(f, p))
                            .map(p -> f + " -> " + p))
                    .toList();
        }

        // then
        assertTrue(violations.isEmpty(), "server must stay reusable, found: " + violations);
    }

    private static boolean contains(Path file, String text) {
        try {
            return Files.readString(file).contains(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
