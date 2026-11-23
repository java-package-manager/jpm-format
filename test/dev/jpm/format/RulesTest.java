package dev.jpm.format;

import dev.jpm.format.api.Finding;
import dev.jpm.format.api.Fix;
import dev.jpm.format.api.RuleTester;
import dev.jpm.format.rules.EmptyCatchBlock;
import dev.jpm.format.rules.ImportOrder;
import dev.jpm.format.rules.JavaFormat;
import dev.jpm.format.rules.LicenseHeader;
import dev.jpm.format.rules.MissingOverrideAnnotation;
import dev.jpm.format.rules.NonJavadocComment;
import dev.jpm.format.rules.PointlessBooleanExpression;
import dev.jpm.format.rules.StringEquality;
import dev.jpm.format.rules.UnnecessaryModifier;
import dev.jpm.format.rules.UnusedImport;
import dev.jpm.format.rules.UnusedLocalVariable;
import dev.jpm.format.rules.UnusedPrivateMember;
import org.junit.jupiter.api.Test;

import java.time.Year;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesTest {
    @Test
    void javaFormatLaysOutCodeLikeIntelliJ() {
        RuleTester.of(new JavaFormat()).assertFormats("class A{int x;void f(){if(x>0){x--;}}}", """
                class A {
                    int x;

                    void f() {
                        if (x > 0) {
                            x--;
                        }
                    }
                }
                """);
    }

    @Test
    void javaFormatWithTabsKeepsTextBlocks() {
        String out = RuleTester.of(new JavaFormat())
                .config("{\"formatter\": {\"useTabs\": true}}")
                .format("class A { String s = \"\"\"\n    keep\n    \"\"\"; void f() { int y = 1; } }");
        assertTrue(out.contains("\n\tvoid f() {\n\t\tint y = 1;"), out);
        assertTrue(out.contains("\n    keep\n    \"\"\";"), out);
    }

    @Test
    void javaFormatWithTwoSpaceIndent() {
        String out = RuleTester.of(new JavaFormat())
                .config("{\"formatter\": {\"lineWidth\": 100, \"indentWidth\": 2}}")
                .format("class A { void f() { } }");
        assertEquals("class A {\n  void f() {}\n}\n", out);
    }

    @Test
    void javaFormatWithAnyWidthAndIndent() {
        String in = "class A { void f() { if (true) { call(aaaaaaaaaa, bbbbbbbbbb, cccccccccc, dddddddddd); } } }";
        RuleTester palantir = RuleTester.of(new JavaFormat())
                .config("{\"formatter\": {\"style\": \"palantir\", \"lineWidth\": 50, \"indentWidth\": 3}}");
        palantir.assertFormats(in, """
                class A {
                   void f() {
                      if (true) {
                         call(
                               aaaaaaaaaa,
                               bbbbbbbbbb,
                               cccccccccc,
                               dddddddddd);
                      }
                   }
                }
                """);
        RuleTester intellij =
                RuleTester.of(new JavaFormat()).config("{\"formatter\": {\"lineWidth\": 50, \"indentWidth\": 3}}");
        intellij.assertFormats(in, """
                class A {
                   void f() {
                      if (true) {
                         call(aaaaaaaaaa, bbbbbbbbbb, cccccccccc, dddddddddd);
                      }
                   }
                }
                """);
    }

    @Test
    void importOrderFollowsIntelliJLayout() {
        RuleTester.of(new ImportOrder()).assertFormats("""
                package p;

                import static org.junit.Assert.assertTrue;
                import javax.swing.JFrame;
                import java.util.List;
                import com.google.Foo;
                import java.util.ArrayList;
                import org.Bar;
                import static java.util.Objects.requireNonNull;
                import com.google.Foo;

                class A {}
                """, """
                package p;

                import com.google.Foo;
                import org.Bar;

                import java.util.ArrayList;
                import java.util.List;
                import javax.swing.JFrame;

                import static java.util.Objects.requireNonNull;
                import static org.junit.Assert.assertTrue;

                class A {}
                """);
    }

    @Test
    void importOrderCustomGroupsAndWildcards() {
        RuleTester t = RuleTester.of(new ImportOrder()).config("""
                { "imports": { "groups": ["java.*", "*"], "wildcardThreshold": 2 } } // static goes last by default
                """);
        assertEquals(
                """
                import java.util.*;

                import a.B;
                import static x.Y.z;
                class A {}
                """,
                t.format(
                        "import a.B;\nimport java.util.Map;\nimport static x.Y.z;\nimport java.util.List;\nclass A {}\n"));
    }

    @Test
    void importOrderLeavesCommentedImportsAlone() {
        String in = "import b.B;\n// keep me\nimport a.A;\nclass C {}\n";
        assertEquals(in, RuleTester.of(new ImportOrder()).format(in));
    }

    @Test
    void unusedImportsAreRemoved() {
        RuleTester t = RuleTester.of(new UnusedImport());
        String in = """
                package p;

                import java.util.List;
                import java.util.Map;
                import java.lang.String;
                import p.Same;
                import java.util.Set;
                import java.util.Set;
                import java.io.*;
                import static java.util.Objects.requireNonNull;
                import static java.util.Objects.hash;

                /** See {@link Map}. */
                class A { Set<String> s; Object o = requireNonNull(null); }
                """;
        t.assertFormats(in, """
                package p;

                import java.util.Map;
                import java.util.Set;
                import java.io.*;
                import static java.util.Objects.requireNonNull;

                /** See {@link Map}. */
                class A { Set<String> s; Object o = requireNonNull(null); }
                """);
        assertEquals(
                List.of(
                        "3:1 style/UnusedImport Unused import 'java.util.List'",
                        "5:1 style/UnusedImport Redundant import 'java.lang.String': java.lang is imported implicitly",
                        "6:1 style/UnusedImport Redundant import 'p.Same': same package",
                        "8:1 style/UnusedImport Duplicate import 'java.util.Set'",
                        "11:1 style/UnusedImport Unused import 'static java.util.Objects.hash'"),
                t.findings(in));
    }

    @Test
    void unnecessaryModifiersAreRemoved() {
        RuleTester.of(new UnnecessaryModifier()).assertFormats("""
                interface I {
                    public abstract void f();
                    public static final int X = 1;
                    public default void g() {}
                    public static class N {}
                }
                class C {
                    static interface J {}
                    static enum E { A; private E() {} }
                    final record R() {}
                    static class Keep {}
                }
                """, """
                interface I {
                    void f();
                    int X = 1;
                    default void g() {}
                    class N {}
                }
                class C {
                    interface J {}
                    enum E { A; E() {} }
                    record R() {}
                    static class Keep {}
                }
                """);
    }

    @Test
    void licenseHeaderIsAddedAndKeepsItsYear() {
        RuleTester t = RuleTester.of(new LicenseHeader())
                .config("{\"formatter\": {\"licenseHeader\": \"/* (c) $YEAR Acme */\"}}");
        int year = Year.now().getValue();
        t.assertFormats(
                "// old header\npackage p;\nclass A {}\n", "/* (c) " + year + " Acme */\n\npackage p;\nclass A {}\n");
        t.assertFormats(
                "/* (c) 2019-2021 Acme */\n\npackage p;\nclass A {}\n",
                "/* (c) 2019-2021 Acme */\n\npackage p;\nclass A {}\n");
        t.assertFormats("/** Docs. */\nclass A {}\n", "/* (c) " + year + " Acme */\n\n/** Docs. */\nclass A {}\n");
    }

    @Test
    void licenseHeaderMustBeAComment() {
        FormatException e = assertThrows(
                FormatException.class,
                () -> RuleTester.of(new LicenseHeader())
                        .config("{\"formatter\": {\"licenseHeader\": \"Copyright me\"}}")
                        .format("class A {}"));
        assertTrue(e.getMessage().contains("must be a Java comment"), e.getMessage());
    }

    @Test
    void pointlessBooleanExpressions() {
        RuleTester t = RuleTester.of(new PointlessBooleanExpression());
        assertEquals(
                List.of(
                        "1:35 complexity/PointlessBooleanExpression 'b == true' can be simplified to 'b'",
                        "1:53 complexity/PointlessBooleanExpression 'false != b' can be simplified to 'b'",
                        "1:72 complexity/PointlessBooleanExpression 'f() == false' can be simplified to '!f()'",
                        "1:93 complexity/PointlessBooleanExpression 'b && true' can be simplified to 'b'"),
                t.findings(
                        "class A { boolean b; void g(){if (b == true) {} if (false != b) {} if (f() == false) {} if (b && true) {} if (b && false) {} } boolean f(){return b;} }"));
        var fix = t.check("class A { boolean b = (1 > 2) != true; }").get(0).fix();
        assertEquals("class A { boolean b = !(1 > 2); }", fix.apply("class A { boolean b = (1 > 2) != true; }"));
    }

    @Test
    void nonJavadocComments() {
        String source = """
                /** Kept. */
                class A {
                    // gone with its line
                    int a = 1; // gone with the space before it
                    String s = "// not a comment" + '/' + \"""
                        /* not one either */
                        \""";
                    /* gone */
                    void f() {} // jpm-ignore lint/style/NonJavadocComment: kept
                    //noinspection unused
                    int b;
                }
                """;
        assertTrue(RuleTester.of(new NonJavadocComment()).check(source).stream().noneMatch(f -> f.rule().equals("NonJavadocComment")));
        RuleTester t = RuleTester.of(new NonJavadocComment())
                .config("{\"linter\": {\"rules\": {\"style\": {\"NonJavadocComment\": \"error\"}}}}");
        List<Finding> found = t.check(source).stream().filter(f -> f.rule().equals("NonJavadocComment")).toList();
        assertEquals(
                List.of(
                        "3:5 style/NonJavadocComment Comment that is not Javadoc",
                        "4:16 style/NonJavadocComment Comment that is not Javadoc",
                        "8:5 style/NonJavadocComment Comment that is not Javadoc"),
                found.stream().map(Finding::brief).toList());
        List<Fix.Edit> edits = found.stream().flatMap(f -> f.fix().edits().stream()).toList();
        assertEquals("""
                /** Kept. */
                class A {
                    int a = 1;
                    String s = "// not a comment" + '/' + \"""
                        /* not one either */
                        \""";
                    void f() {} // jpm-ignore lint/style/NonJavadocComment: kept
                    //noinspection unused
                    int b;
                }
                """, Fix.apply(source, edits));
    }

    @Test
    void emptyCatchBlocks() {
        RuleTester t = RuleTester.of(new EmptyCatchBlock());
        assertEquals(
                List.of("1:29 suspicious/EmptyCatchBlock Empty catch block hides the exception"),
                t.findings("class A { void f() { try {} catch (Exception e) {} "
                        + "try {} catch (Exception ignored) {} try {} catch (Exception e) { /* fine */ } } }"));
    }

    @Test
    void unusedLocalsAndParameters() {
        RuleTester t = RuleTester.of(new UnusedLocalVariable());
        assertEquals(
                List.of(
                        "3:13 correctness/UnusedLocalVariable Local variable 'a' is never used",
                        "6:34 correctness/UnusedLocalVariable Parameter 'q' is never used",
                        "8:18 correctness/UnusedLocalVariable Local variable 'k' is never used"),
                t.findings("""
                class A {
                    void f(int p) {
                        int a = 1;
                        int b = 2; Runnable r = () -> System.out.println(b); r.run();
                    }
                    private void g(int used, int q) { System.out.println(used); }
                    void h() {
                        for (int k = 0, n = 3; n > 0; n--) {}
                        int ignored = 0;
                    }
                }
                """));
    }

    @Test
    void unusedPrivateMembers() {
        RuleTester t = RuleTester.of(new UnusedPrivateMember());
        assertEquals(
                List.of(
                        "2:17 correctness/UnusedPrivateMember Private field 'dead' is never used",
                        "5:18 correctness/UnusedPrivateMember Private method 'never()' is never used",
                        "8:50 correctness/UnusedPrivateMember Private field 'notUsedStatic' is never used"),
                t.findings("""
                class A implements java.io.Serializable {
                    private int dead;
                    private int live = 1;
                    private static final long serialVersionUID = 1L;
                    private void never() {}
                    private void used() {}
                    @Deprecated private int annotated;
                    record R(int component) { private static int notUsedStatic; }
                    int get() { used(); return this.live; }
                }
                """));
    }

    @Test
    void typeAwareRules() {
        RuleTester t = RuleTester.of(new MissingOverrideAnnotation(), new StringEquality())
                .withTypes();
        String in = """
                class A implements Comparable<A> {
                    public int compareTo(A o) { return 0; }
                    public String toString() { return ""; }
                    @Override public int hashCode() { return 1; }
                    public void own() {}
                    boolean eq(String a, String b) { return a == b || a != null; }
                }
                """;
        assertEquals(
                List.of(
                        "2:16 style/MissingOverrideAnnotation Method 'compareTo' overrides a supertype method but has no @Override",
                        "3:19 style/MissingOverrideAnnotation Method 'toString' overrides a supertype method but has no @Override",
                        "6:45 correctness/StringEquality String compared with '==' instead of equals()"),
                t.findings(in));
        var fixes = t.check(in);
        assertTrue(fixes.get(0).fix().apply(in).contains("\n    @Override\n    public int compareTo(A o)"));
        assertTrue(fixes.get(2).fix().apply(in).contains("return a.equals(b) || a != null;"));
    }

    @Test
    void typeAwareRulesAreSkippedWithoutTypes() {
        assertEquals(
                List.of(),
                RuleTester.of(new StringEquality())
                        .findings("class A { boolean eq(String a, String b) { return a == b; } }"));
    }
}
