package dev.jpm.format.intellij;

import dev.jpm.format.api.RuleTester;
import dev.jpm.format.rules.JavaFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The IntelliJ rules, each right after the layout engine as jpm format runs them.
 */
class IntelliJRulesTest {
    private static RuleTester intellij() {
        return RuleTester.of(
                new JavaFormat(),
                new EmptyBodies(),
                new MemberBlankLines(),
                new KeepBlankLines(),
                new JavadocLayout(),
                new LineCommentSpacing());
    }

    @Test
    void blankLinesAreKeptUpToTwo() {
        intellij().assertFormats("""
                class A {
                    void f() {
                        int x = 1;



                        int y = 2;

                    }


                    int c;
                }
                """, """
                class A {
                    void f() {
                        int x = 1;


                        int y = 2;

                    }


                    int c;
                }
                """);
    }

    @Test
    void maxBlankLinesIsConfigurable() {
        String in = "class A {\n    int a;\n\n\n\n\n    int b;\n}\n";
        assertEquals(
                "class A {\n    int a;\n\n    int b;\n}\n",
                intellij().config("{\"formatter\": {\"maxBlankLines\": 1}}").format(in));
        assertEquals(
                "class A {\n    int a;\n\n\n\n    int b;\n}\n",
                intellij().config("{\"formatter\": {\"maxBlankLines\": 3}}").format(in));
    }

    @Test
    void linesTheLayoutJoinsStayJoined() {
        intellij()
                .assertFormats(
                        "class A {\n    int x = f(1,\n\n            2);\n}\n", "class A {\n    int x = f(1, 2);\n}\n");
    }

    @Test
    void blankLinesAroundMembersComeBeforeTheirComments() {
        intellij().assertFormats("""
                class A {
                    int a;
                    int b;
                    void f() {}
                    /** Docs. */
                    @Deprecated
                    void g() {}
                    // a comment
                    class Inner {}
                    static {}
                    enum E { X, Y; void m() {} }
                    record R(int x, int y) { R {} }
                }
                """, """
                class A {
                    int a;
                    int b;

                    void f() {
                    }

                    /**
                     * Docs.
                     */
                    @Deprecated
                    void g() {
                    }

                    // a comment
                    class Inner {
                    }

                    static {
                    }

                    enum E {
                        X,
                        Y;

                        void m() {
                        }
                    }

                    record R(int x, int y) {
                        R {
                        }
                    }
                }
                """);
    }

    @Test
    void emptyBodiesGoOnTwoLinesExceptLambdasAndAnonymousClasses() {
        intellij().assertFormats("""
                class A {
                    A() {}
                    void f(boolean b) {
                        if (b) {} else {}
                        for (;;) {}
                        while (b) {}
                        try {} catch (Exception e) {} finally {}
                        synchronized (this) {}
                        Runnable r = () -> {};
                        Object o = new Object() {};
                        int[] none = {};
                    }
                    interface I {}
                }
                """, """
                class A {
                    A() {
                    }

                    void f(boolean b) {
                        if (b) {
                        } else {
                        }
                        for (; ; ) {
                        }
                        while (b) {
                        }
                        try {
                        } catch (Exception e) {
                        } finally {
                        }
                        synchronized (this) {
                        }
                        Runnable r = () -> {};
                        Object o = new Object() {};
                        int[] none = {};
                    }

                    interface I {
                    }
                }
                """);
    }

    @Test
    void crlfFilesStayCrlf() {
        String out = intellij().format("class A {\r\n    void f() {}\r\n\r\n\r\n    int x;\r\n}\r\n");
        assertEquals("class A {\r\n    void f() {\r\n    }\r\n\r\n\r\n    int x;\r\n}\r\n", out);
    }

    @Test
    void palantirStyleTurnsThemOff() {
        String in = "class A {\n    void f() {}\n\n\n    int x;\n}\n";
        assertEquals(
                RuleTester.of(new JavaFormat()).format(in),
                RuleTester.withBuiltins()
                        .config("{\"formatter\": {\"style\": \"palantir\"}}")
                        .format(in));
        assertTrue(RuleTester.withBuiltins().format(in).contains("void f() {\n    }"));
    }

    @Test
    void theirOutputPassesCheck() {
        RuleTester all = RuleTester.withBuiltins();
        String formatted = all.format("class A { void f() {}\n\n\n    /** D. */ void g() {} }");
        assertEquals(List.of(), all.findings(formatted));
    }

    @Test
    void javadocTagDescriptionsAlignAndATagListFollowsABlankLine() {
        intellij().assertFormats("""
                class A {
                    /**
                     * Adds.
                     * @param a  the first
                     * @param total the sum, which goes on
                     *   to the next line
                     * @return the result, also
                     *     continued
                     * @throws IllegalArgumentException if a is negative
                     * @throws NullPointerException
                     *     if total is null
                     */
                    int add(int a, Integer total) {
                        return a + total;
                    }
                }
                """, """
                class A {
                    /**
                     * Adds.
                     *
                     * @param a     the first
                     * @param total the sum, which goes on
                     *              to the next line
                     * @return the result, also
                     * continued
                     * @throws IllegalArgumentException if a is negative
                     * @throws NullPointerException     if total is null
                     */
                    int add(int a, Integer total) {
                        return a + total;
                    }
                }
                """);
    }

    @Test
    void oneLineJavadocTakesThreeLinesAndParagraphsGetP() {
        intellij().assertFormats("""
                class A {
                    /** The count. */
                    int count;

                    /**
                     * First paragraph.
                     *
                     * Second paragraph.
                     *
                     * <ul><li>a list stays</li></ul>
                     */
                    void f() {
                    }
                }
                """, """
                class A {
                    /**
                     * The count.
                     */
                    int count;

                    /**
                     * First paragraph.
                     * <p>
                     * Second paragraph.
                     *
                     * <ul><li>a list stays</li></ul>
                     */
                    void f() {
                    }
                }
                """);
    }

    @Test
    void referencesGetASpaceAfterEachComma() {
        intellij().assertFormats("""
                class A {
                    /**
                     * Like {@link String#format(String,Object...)} and {@linkplain #f(int,int) f}.
                     *
                     * @see #f(int,int)
                     */
                    void f(int a, int b) {
                    }
                }
                """, """
                class A {
                    /**
                     * Like {@link String#format(String, Object...)} and {@linkplain #f(int, int) f}.
                     *
                     * @see #f(int, int)
                     */
                    void f(int a, int b) {
                    }
                }
                """);
    }

    @Test
    void lineCommentsKeepTheirTextRightAfterTheSlashes() {
        intellij().assertFormats("""
                class A {
                    //TODO later
                    int a = 1; //$NON-NLS-1$
                    // already spaced
                    int b;
                }
                """, """
                class A {
                    //TODO later
                    int a = 1; //$NON-NLS-1$
                    // already spaced
                    int b;
                }
                """);
    }
}
