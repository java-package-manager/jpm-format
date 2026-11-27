package dev.jpm.format.intellij;

import dev.jpm.format.api.RuleTester;
import dev.jpm.format.rules.JavaFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void javadocTagsAreInIntelliJsOrder() {
        intellij().assertFormats("""
                class A {
                    /**
                     * Method.
                     *
                     * @since 1
                     * @see Object
                     * @deprecated old
                     *             reason
                     * @exception IllegalStateException ise
                     * @author me
                     * @return r
                     * @param b the b
                     * @param a the a
                     */
                    int m(int a, int b) {
                        return 0;
                    }
                }
                """, """
                class A {
                    /**
                     * Method.
                     *
                     * @param b the b
                     * @param a the a
                     * @return r
                     * @throws IllegalStateException ise
                     * @author me
                     * @see Object
                     * @since 1
                     * @deprecated old
                     * reason
                     */
                    int m(int a, int b) {
                        return 0;
                    }
                }
                """);
    }

    @Test
    void javadocLinesLoseTheirIndentOutsidePreAndLists() {
        intellij().assertFormats("""
                class A {
                    /**
                     * Text.
                     *  more text
                     * <pre>
                     *   kept
                     *  </pre>
                     *  after
                     * <blockquote>
                     *    quoted
                     * </blockquote>
                     *  <ul>
                     *    <li>item
                     *  </ul>
                     *  last
                     *
                     * @return r
                     *   more r
                     */
                    int f() {
                        return 0;
                    }
                }
                """, """
                class A {
                    /**
                     * Text.
                     * more text
                     * <pre>
                     *   kept
                     *  </pre>
                     * after
                     * <blockquote>
                     * quoted
                     * </blockquote>
                     * <ul>
                     *   <li>item
                     * </ul>
                     * last
                     *
                     * @return r
                     * more r
                     */
                    int f() {
                        return 0;
                    }
                }
                """);
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
                intellij().config("{\"java\": {\"maxBlankLines\": 1}}").format(in));
        assertEquals(
                "class A {\n    int a;\n\n\n\n    int b;\n}\n",
                intellij().config("{\"java\": {\"maxBlankLines\": 3}}").format(in));
    }

    /**
     * Modifiers keep their order; the annotations and modifiers of a local variable join its type's line; the
     * blank line before a method goes after a comment that is not its Javadoc; a wrapped throws list is indented from
     * the method; a comment after the statements of a case stays with them; a block comment's lines move with its
     * first. IntelliJ IDEA's own output.
     */
    @Test
    void declarationsAndCommentsLikeIntelliJ() {
        intellij().assertFormats("""
                class G {
                    private final static int A = 1;
                    private int b;

                    // Constructor
                    /**
                     * Docs.
                     */
                    G() {
                    }

                    void m(Object in, int c) throws java.io.IOException,
                            IllegalStateException {
                        @SuppressWarnings("unchecked")
                        final String s = (String) in;
                        switch (c) {
                            case 1:
                                s.length();
                                // falls through
                            case 2:
                                break;
                        }
                                /*
                                 * A block comment
                                 *   indented
                                 */
                        s.length();
                    }
                }
                """, """
                class G {
                    private final static int A = 1;
                    private int b;

                    // Constructor

                    /**
                     * Docs.
                     */
                    G() {
                    }

                    void m(Object in, int c) throws java.io.IOException,
                            IllegalStateException {
                        @SuppressWarnings("unchecked") final String s = (String) in;
                        switch (c) {
                            case 1:
                                s.length();
                                // falls through
                            case 2:
                                break;
                        }
                        /*
                         * A block comment
                         *   indented
                         */
                        s.length();
                    }
                }
                """);
    }

    @Test
    void blankLinesInsideAnExpressionAreKept() {
        intellij().assertFormats(
                "class A {\n    int x = f(1,\n\n 2);\n}\n", "class A {\n    int x = f(1,\n\n            2);\n}\n");
    }

    /**
     * Lines stay broken where they were: one continuation indent from the line a wrapped line continues, blocks
     * from the line they start on, method parameters lined up. IntelliJ IDEA's own output.
     */
    @Test
    void wrappedLinesAreIndentedFromTheLineTheyContinue() {
        intellij().assertFormats("""
                class C {
                void m() {
                foo(bar(a,
                b));
                foo(
                a, bar(b,
                c));
                foo(x -> {
                body();
                });
                foo(a,
                x -> {
                body();
                });
                a.b()
                .c(d,
                e);
                int x = a +
                b;
                String s = cond
                ? a
                : b;
                foo(a, new Runnable() {
                public void run() {
                }
                });
                }
                public <V> C(final Map<String, V> valueMap, final String prefix,
                final char escape) {
                }
                public <V> C(
                final Map<String, V> valueMap, final String prefix,
                final char escape) {
                }
                }
                """, """
                class C {
                    void m() {
                        foo(bar(a,
                                b));
                        foo(
                                a, bar(b,
                                        c));
                        foo(x -> {
                            body();
                        });
                        foo(a,
                                x -> {
                                    body();
                                });
                        a.b()
                                .c(d,
                                        e);
                        int x = a +
                                b;
                        String s = cond
                                ? a
                                : b;
                        foo(a, new Runnable() {
                            public void run() {
                            }
                        });
                    }

                    public <V> C(final Map<String, V> valueMap, final String prefix,
                                 final char escape) {
                    }

                    public <V> C(
                            final Map<String, V> valueMap, final String prefix,
                            final char escape) {
                    }
                }
                """);
    }

    /**
     * Line breaks where the layout has no break of its own (after an operator, before a closing parenthesis) are
     * kept; the else after a } is joined; for and try headers line up. IntelliJ IDEA's own output.
     */
    @Test
    void lineBreaksAreKeptLikeIntelliJ() {
        intellij().assertFormats("""
                class D
                extends Object
                implements Runnable {
                @Override public void run() {}
                @Nullable private String s;
                @Deprecated
                private String t;
                void m() throws
                Exception {
                int x = f(1,

                2);
                a(); b();
                int[] q = {1, 2,};
                int[] r = new int[] {1, 2};
                enum E { A, B; int y; }
                if (x) foo();
                Runnable l = () -> {
                };
                foo(a
                );
                int y =
                5;
                String s = "a"
                + "b";
                z = a.
                b();
                @SuppressWarnings("x") final int w = 1;
                for (int i = 0;
                i < 10;
                i++) {
                }
                try (A a = new A();
                B b = new B()) {
                }
                }
                void n()
                throws Exception {
                }
                }
                """, """
                class D
                        extends Object
                        implements Runnable {
                    @Override
                    public void run() {
                    }

                    @Nullable
                    private String s;
                    @Deprecated
                    private String t;

                    void m() throws
                            Exception {
                        int x = f(1,

                                2);
                        a();
                        b();
                        int[] q = {1, 2,};
                        int[] r = new int[]{1, 2};
                        enum E {
                            A, B;
                            int y;
                        }
                        if (x) foo();
                        Runnable l = () -> {
                        };
                        foo(a
                        );
                        int y =
                                5;
                        String s = "a"
                                + "b";
                        z = a.
                                b();
                        @SuppressWarnings("x") final int w = 1;
                        for (int i = 0;
                             i < 10;
                             i++) {
                        }
                        try (A a = new A();
                             B b = new B()) {
                        }
                    }

                    void n()
                            throws Exception {
                    }
                }
                """);
    }

    /**
     * Comments at the first column stay there, line comments are not wrapped and keep their spacing, and an if
     * after an else and a comment is the else's statement. IntelliJ IDEA's own output.
     */
    @Test
    void commentsAndChainsLikeIntelliJ() {
        intellij().assertFormats("""
                class F {
                private static final String[][] X = {
                {"a", "b"}, // c
                {"d", "e"},
                };
                int y;  // two spaces
                void m() {
                //        commented(out);
                if (a) {
                x();
                } else // comment
                if (b) {
                y();
                }
                switch (c) {
                case 1:// fall
                z();
                // fall through
                case 2:
                break;
                }
                Object o = new Builder()
                .with(a,
                b)
                .build(
                );
                // a very long comment that goes past the right margin of one hundred and twenty columns, which IntelliJ leaves alone
                long v = aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa + bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb + ccccc;
                }
                }
                """, """
                class F {
                    private static final String[][] X = {
                            {"a", "b"}, // c
                            {"d", "e"},
                    };
                    int y;  // two spaces

                    void m() {
                //        commented(out);
                        if (a) {
                            x();
                        } else // comment
                            if (b) {
                                y();
                            }
                        switch (c) {
                            case 1:// fall
                                z();
                // fall through
                            case 2:
                                break;
                        }
                        Object o = new Builder()
                                .with(a,
                                        b)
                                .build(
                                );
                // a very long comment that goes past the right margin of one hundred and twenty columns, which IntelliJ leaves alone
                        long v = aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa + bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb + ccccc;
                    }
                }
                """);
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
                        X, Y;

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
    void theirOutputPassesCheck() {
        RuleTester all = RuleTester.withBuiltins();
        String formatted = all.format("class A { void f() {}\n\n\n    /** D. */ void g() {} }");
        assertEquals(List.of(), all.findings(formatted));
    }

    @Test
    void javadocWithPreInItsTagsIsFormattedToo() {
        intellij().assertFormats("""
                class A {
                    /**
                     * Adds.
                     * @param a  the first
                     * @param bb  the second, as in
                     * <pre>
                     * add(1,  2)
                     * </pre>
                     */
                    void f(int a, int bb) {
                    }
                }
                """, """
                class A {
                    /**
                     * Adds.
                     *
                     * @param a  the first
                     * @param bb the second, as in
                     *           <pre>
                     *           add(1,  2)
                     *           </pre>
                     */
                    void f(int a, int bb) {
                    }
                }
                """);
    }

    @Test
    void javadocBlockTagsHaveNoBlankLinesBetweenThem() {
        intellij().assertFormats("""
                class A {
                    /**
                     * Adds.
                     *
                     * @param a the first
                     *
                     * @return   the sum
                     *
                     *
                     * @since 1.0
                     */
                    int add(int a) {
                        return a;
                    }
                }
                """, """
                class A {
                    /**
                     * Adds.
                     *
                     * @param a the first
                     * @return the sum
                     * @since 1.0
                     */
                    int add(int a) {
                        return a;
                    }
                }
                """);
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
