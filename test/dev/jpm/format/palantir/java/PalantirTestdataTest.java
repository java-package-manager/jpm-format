package dev.jpm.format.palantir.java;

import dev.jpm.format.Exports;
import dev.jpm.format.palantir.Newlines;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upstream's formatter test cases: each {@code Name.input} formats to {@code Name.output}, and formatting an
 * output again leaves it unchanged with LF, CR and CRLF line endings. The fork started out byte-identical to
 * Palantir Java Format 2.103.0; when jpm-format changes the layout on purpose, the outputs change with it.
 */
class PalantirTestdataTest {
    /** Cases that need a newer Java to parse, as upstream's FileBasedTests lists them. */
    private static final Map<String, Integer> MIN_JAVA = Map.ofEntries(
            Map.entry("Records", 14),
            Map.entry("RSL", 14),
            Map.entry("Var", 14),
            Map.entry("ExpressionSwitch", 14),
            Map.entry("I574", 14),
            Map.entry("I594", 14),
            Map.entry("I603", 15),
            Map.entry("I588", 16),
            Map.entry("I683", 17),
            Map.entry("I684", 17),
            Map.entry("I696", 17),
            Map.entry("SwitchGuardClause", 21),
            Map.entry("SwitchRecord", 21),
            Map.entry("SwitchDouble", 21),
            Map.entry("SwitchUnderscore", 21),
            Map.entry("I880", 21),
            Map.entry("I1309", 21),
            Map.entry("Unnamed", 21));

    @BeforeAll
    static void openJavac() {
        assertTrue(Exports.open(), "javac's internals must be exported");
    }

    private static Formatter formatter() {
        return new Formatter(
                JavaFormatterOptions.builder()
                        .style(JavaFormatterOptions.Style.PALANTIR)
                        .build(),
                false);
    }

    private static Path dir() throws URISyntaxException {
        var url = PalantirTestdataTest.class.getClassLoader().getResource("palantir/testdata");
        assertTrue(url != null, "test/assets/palantir/testdata is not on the test classpath");
        return Path.of(url.toURI());
    }

    @TestFactory
    Stream<DynamicTest> testdata() throws IOException, URISyntaxException {
        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir())) {
            for (Path input :
                    files.filter(p -> p.toString().endsWith(".input")).sorted().toList()) {
                String name = input.getFileName().toString().replaceFirst("\\.input$", "");
                Path output = input.resolveSibling(name + ".output");
                tests.add(DynamicTest.dynamicTest(name, () -> check(name, input, output)));
            }
        }
        assertEquals(226, tests.size(), "upstream's 226 test cases");
        return tests.stream();
    }

    private static void check(String name, Path inputFile, Path outputFile) throws Exception {
        Integer min = MIN_JAVA.get(name);
        Assumptions.assumeTrue(min == null || Runtime.version().feature() >= min, "needs Java " + min);
        String input = Files.readString(inputFile, StandardCharsets.UTF_8);
        String expected = Files.readString(outputFile, StandardCharsets.UTF_8);
        Formatter formatter = formatter();
        String output = StringWrapper.wrap(
                JavaFormatterOptions.Style.PALANTIR.maxLineLength(), formatter.formatSource(input), formatter);
        assertEquals(expected, output, "bad output for " + name);
        String separator = Newlines.getLineEnding(expected);
        for (String ending : List.of("\n", "\r", "\r\n")) {
            String mangled = expected.replace(separator, ending);
            assertEquals(mangled, formatter().formatSource(mangled), "not idempotent for " + name);
        }
    }
}
