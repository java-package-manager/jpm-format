package dev.jpm.format.intellij;

import dev.jpm.format.api.RuleTester;
import dev.jpm.format.rules.JavaFormat;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The IntelliJ style's own layout engine ({@link Layout}, ADR 0006) with the IntelliJ rules after it, as
 * {@code jpm format} runs them. The cases in {@code test/assets/intellij/layout} are unformatted code
 * ({@code .input}) and what IntelliJ IDEA's command-line formatter makes of it with its default code style
 * ({@code .output}).
 */
class LayoutTest {
    private static RuleTester intellij() {
        return RuleTester.of(
                new JavaFormat(),
                new EmptyBodies(),
                new MemberBlankLines(),
                new KeepBlankLines(),
                new JavadocLayout(),
                new LineCommentSpacing());
    }

    @TestFactory
    Stream<DynamicTest> formatsLikeIntelliJ() throws IOException, URISyntaxException {
        var url = LayoutTest.class.getClassLoader().getResource("intellij/layout");
        assertTrue(url != null, "test/assets/intellij/layout is not on the test classpath");
        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
            for (Path input : files.filter(p -> p.toString().endsWith(".input")).sorted().toList()) {
                String name = input.getFileName().toString().replaceFirst("\\.input$", "");
                Path output = input.resolveSibling(name + ".output");
                tests.add(DynamicTest.dynamicTest(name, () -> {
                    String in = Files.readString(input, StandardCharsets.UTF_8);
                    String expected = Files.readString(output, StandardCharsets.UTF_8);
                    intellij().assertFormats(in, expected);
                    intellij().assertFormats(expected, expected);
                    String crlf = expected.replace("\n", "\r\n");
                    assertEquals(crlf, intellij().format(in.replace("\n", "\r\n")), name + " with CRLF");
                }));
            }
        }
        assertEquals(4, tests.size());
        return tests.stream();
    }

    @Test
    void literalsJavacFoldsAreStillSpacedAsOperators() {
        intellij().assertFormats("""
                class A {
                    int f() {
                        String s = "a"+"b"
                        +"c";
                        return-1;
                    }
                }
                """, """
                class A {
                    int f() {
                        String s = "a" + "b"
                                + "c";
                        return -1;
                    }
                }
                """);
    }

    @Test
    void argumentsOfAChainAreIndentedFromWhereTheChainStarts() {
        intellij().assertFormats("""
                class A {
                    Object o =
                          new Lookup(
                            new String[][] {
                              {"a", "b"},
                          }).with(
                            new Lookup(X)
                          ).with(
                            Y
                        );
                }
                """, """
                class A {
                    Object o =
                            new Lookup(
                                    new String[][]{
                                            {"a", "b"},
                                    }).with(
                                    new Lookup(X)
                            ).with(
                                    Y
                            );
                }
                """);
    }

    @Test
    void multilineConditionsAreIndentedFromTheirFirstLine() {
        intellij().assertFormats("""
                class A {
                    boolean f() {
                        if (!allowSigns
                                && (c == 'd'
                                || c == 'D')) {
                            return
                                    a &&
                                    b;
                        }
                        return x
                                && m(chars,
                                pos);
                    }
                }
                """, """
                class A {
                    boolean f() {
                        if (!allowSigns
                                && (c == 'd'
                                || c == 'D')) {
                            return
                                    a &&
                                            b;
                        }
                        return x
                                && m(chars,
                                pos);
                    }
                }
                """);
    }

    @Test
    void enumConstantsWithAnnotationsAndDocs() {
        intellij().assertFormats("""
                enum E {
                /** One. */
                @Deprecated
                ONE,
                @API(status = STABLE) TWO;
                }
                """, """
                enum E {
                    /**
                     * One.
                     */
                    @Deprecated
                    ONE,
                    @API(status = STABLE) TWO;
                }
                """);
    }

    @Test
    void modulesPackagesAndInitializers() {
        intellij().path("module-info.java").assertFormats(
                "module a.b {\nexports a.b;\n    requires   c;\n}\n",
                "module a.b {\n    exports a.b;\n    requires c;\n}\n");
        intellij().path("package-info.java").assertFormats(
                "@NullMarked\npackage p;\nimport a.NullMarked;\n",
                "@NullMarked\npackage p;\n\nimport a.NullMarked;\n");
        intellij().assertFormats("class A {\n    static {}\n}\n", "class A {\n    static {\n    }\n}\n");
    }

    @Test
    void textBlocksMoveAsAWhole() {
        intellij().assertFormats("""
                class A {
                    String s = \"""
                  one
                    two
                  \""";
                }
                """, """
                class A {
                    String s = \"""
                            one
                              two
                            \""";
                }
                """);
    }
}
