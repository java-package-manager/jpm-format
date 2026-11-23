package dev.jpm.format;

import dev.jpm.format.api.Context;
import dev.jpm.format.api.Finding;
import dev.jpm.format.api.Formatter;
import dev.jpm.format.api.Lint;
import dev.jpm.format.api.RuleTester;
import dev.jpm.format.api.Rules;
import dev.jpm.format.api.Severity;
import dev.jpm.format.rules.JavaFormat;
import dev.jpm.format.rules.PointlessBooleanExpression;
import dev.jpm.format.rules.UnusedLocalVariable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineTest {
    @TempDir
    Path dir;

    private Path write(String rel, String text) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
        return p;
    }

    private Workspace open() {
        return Workspace.open(dir, Project.untyped(dir, "test"), false, w -> {});
    }

    record Named(String id) implements Lint {
        @Override
        public void check(Context ctx) {
        }
    }

    @Test
    void rulesAreOrderedByAddBeforeAndAddAfter() {
        Rules r = new Rules().add(new Named("A")).add(new Named("C"));
        r.addBefore("C", new Named("B")).addAfter("C", new Named("D"));
        assertEquals(List.of("A", "B", "C", "D"), r.all().stream().map(Lint::id).toList());
        assertThrows(IllegalArgumentException.class, () -> r.addBefore("Nope", new Named("E")));
        assertThrows(IllegalArgumentException.class, () -> r.add(new Named("A")));
        assertThrows(IllegalArgumentException.class, () -> r.add(new Named("bad id")));
    }

    @Test
    void defaultMetadata() {
        Lint l = new Named("X");
        assertEquals("custom", l.category());
        assertEquals(Severity.WARN, l.severity());
        assertEquals(Severity.ERROR, new Upper().severity());
    }

    /**
     * Upper-cases "todo" comments; a formatter with the built-in check.
     */
    static final class Upper implements Formatter {
        @Override
        public String id() {
            return "UpperTodo";
        }

        @Override
        public String format(Context ctx) {
            return ctx.source().replace("// todo", "// TODO");
        }
    }

    /**
     * Runs after JavaFormat and undoes one of its choices on purpose.
     */
    static final class NoSpaceAfterIf implements Formatter {
        @Override
        public String id() {
            return "NoSpaceAfterIf";
        }

        @Override
        public String format(Context ctx) {
            return ctx.source().replace("if (", "if(");
        }
    }

    @Test
    void checkJudgesTheWholePipeline() {
        RuleTester t = RuleTester.withBuiltins(new NoSpaceAfterIf());
        String formatted = t.format("class A { void f(boolean b) { if (b) { g(); } } void g() { } }");
        assertTrue(formatted.contains("        if(b) {\n            g();\n        }\n"), formatted);
        assertEquals(List.of(), t.findings(formatted));
        String unformatted = formatted.replace("    void f", "  void f");
        assertEquals(List.of("2:1 format/JavaFormat Code is not formatted"), t.findings(unformatted));
        String spaced = formatted.replace("if(b)", "if (b)");
        assertEquals(List.of("3:1 custom/NoSpaceAfterIf Code is not formatted"), t.findings(spaced));
    }

    @Test
    void formattersAreAlsoLinters() {
        RuleTester t = RuleTester.of(new Upper());
        String in = "class A {\n    // todo one\n    int x;\n}\n";
        List<Finding> fs = t.check(in);
        assertEquals(1, fs.size());
        assertEquals("2:1 custom/UpperTodo Code is not formatted", fs.get(0).brief());
        assertEquals(Severity.ERROR, fs.get(0).severity());
        assertEquals(
                "class A {\n    // TODO one\n    int x;\n}\n", fs.get(0).fix().apply(in));
        assertEquals("class A {\n    // TODO one\n    int x;\n}\n", t.format(in));
    }

    @Test
    void nonIdempotentFormattersAreReportedInDebugMode() {
        Formatter grows = new Formatter() {
            @Override
            public String id() {
                return "Grows";
            }

            @Override
            public String format(Context ctx) {
                return ctx.source() + "//\n";
            }
        };
        List<String> fs = RuleTester.of(grows).findings("class A {}\n");
        assertTrue(
                fs.stream().anyMatch(s -> s.contains("internal/NotIdempotent Grows is not idempotent")), fs.toString());
    }

    @Test
    void brokenFormatterOutputIsDropped() {
        Formatter breaks = new Formatter() {
            @Override
            public String id() {
                return "Breaks";
            }

            @Override
            public String format(Context ctx) {
                return ctx.source().replace("}", "");
            }
        };
        RuleTester t = RuleTester.of(breaks);
        assertEquals("class A {}\n", t.format("class A {}\n"));
    }

    @Test
    void invisibleDifferencesAreNamed() {
        RuleTester t = RuleTester.of(new JavaFormat());
        assertEquals(
                List.of("4:1 format/JavaFormat Missing newline at end of file"),
                t.findings("@NullMarked\npackage p;\n\nimport org.jspecify.annotations.NullMarked;"));
        assertEquals(List.of("1:1 format/JavaFormat Trailing whitespace"), t.findings("class A {}   \n"));
        Formatter lf = new Formatter() {
            @Override
            public String id() {
                return "Lf";
            }

            @Override
            public String format(Context ctx) {
                return ctx.source().replace("\r\n", "\n");
            }
        };
        assertEquals(
                List.of("1:1 custom/Lf Line endings should be LF"),
                RuleTester.of(lf).findings("class A {\r\n}\r\n"));
    }

    @Test
    void crlfFilesKeepTheirLineEndings() {
        String out = RuleTester.withBuiltins()
                .config("{\"formatter\": {\"licenseHeader\": \"/*\\n * Acme\\n */\"}}")
                .format("import b.B;\r\nimport java.util.List;\r\nimport a.A;\r\nclass C { A a; B b; List<A> l; }\r\n");
        assertEquals(
                "/*\r\n * Acme\r\n */\r\n\r\nimport a.A;\r\nimport b.B;\r\n\r\nimport java.util.List;\r\n\r\n"
                        + "class C {\r\n    A a;\r\n    B b;\r\n    List<A> l;\r\n}\r\n",
                out);
    }

    @Test
    void typeAwareRulesWorkInModularProjects() throws IOException {
        write("src/module-info.java", "module com.acme {\n    exports com.acme;\n}\n");
        String code = "package com.acme;\n\npublic class Thing implements Comparable<Thing> {\n"
                + "    public int compareTo(Thing o) {\n        return 0;\n    }\n}\n";
        write("src/com/acme/Thing.java", code);
        write("test/com/acme/ThingTest.java", code.replace("Thing", "ThingTest"));
        Project typed = new Project(
                dir,
                List.of(new Project.Module(dir, List.of(dir.resolve("src"), dir.resolve("test")), List.of())),
                null);
        Workspace ws = Workspace.open(dir, typed, false, w -> {});
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            for (String rel : List.of("src/com/acme/Thing.java", "test/com/acme/ThingTest.java")) {
                List<String> fs =
                        ws.engine.run(rel, Files.readString(dir.resolve(rel)), Engine.Mode.CHECK).findings().stream()
                                .map(Finding::brief)
                                .toList();
                assertEquals(
                        List.of("4:16 style/MissingOverrideAnnotation Method 'compareTo' overrides a supertype method"
                                + " but has no @Override"),
                        fs,
                        rel);
            }
            assertEquals(
                    List.of(),
                    ws.engine
                            .run(
                                    "src/module-info.java",
                                    Files.readString(dir.resolve("src/module-info.java")),
                                    Engine.Mode.CHECK)
                            .findings());
        } finally {
            System.setErr(err);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8), "javac must not print to stderr");
    }

    @Test
    void theFormatterSupportsThisJdk() {
        assertTrue(Jdks.formatterSupported(), Jdks.unsupportedMessage());
    }

    @Test
    void syntaxErrorsAreReported() {
        List<String> fs = RuleTester.of(new JavaFormat()).findings("class A { void f( }");
        assertEquals(1, fs.size());
        assertTrue(fs.get(0).contains("syntax/ParseError"), fs.toString());
    }

    @Test
    void formatterOffRegionsAreKept() {
        RuleTester t = RuleTester.of(new JavaFormat());
        String in = """
                class A {
                    // @formatter:off
                    int[] m = { 1,0,
                                0,1 };
                    // @formatter:on
                    int   x;
                    // jpm-ignore format
                    void   keep( ) { }
                    void   fix( ) { }
                }
                """;
        String out = t.format(in);
        assertTrue(out.contains("    int[] m = { 1,0,\n                0,1 };\n"), out);
        assertTrue(out.contains("    int x;"), out);
        assertTrue(out.contains("    void   keep( ) { }"), out);
        assertTrue(out.contains("    void fix() {}"), out);
        assertEquals(List.of(), t.findings(out));
    }

    @Test
    void suppressionComments() {
        RuleTester t = RuleTester.of(new UnusedLocalVariable(), new PointlessBooleanExpression());
        String in = """
                class A {
                    void f() {
                        // jpm-ignore lint/correctness/UnusedLocalVariable: needed for the debugger
                        int a = 1;
                        int b = 2;
                        // jpm-ignore-start lint/correctness: generated
                        int c = 3;
                        int d = 4;
                        // jpm-ignore-end lint/correctness
                        // jpm-ignore lint/correctness/UnusedLocalVariable
                        int e = 5;
                        // jpm-ignore lint/complexity: nothing to suppress
                        int g = 6;
                        // jpm-ignore lint/nope/Rule: bad
                    }
                    @SuppressWarnings("unused") void h() { int i = 1; }
                    @SuppressWarnings({"UnusedLocalVariable"}) void j() { int k = 1; }
                    void l() {
                        //noinspection UnusedLocalVariable
                        int m = 1;
                    }
                }
                """;
        assertEquals(
                List.of(
                        "5:13 correctness/UnusedLocalVariable Local variable 'b' is never used",
                        "10:9 suppressions/InvalidSuppression Suppression needs a reason: // jpm-ignore lint/correctness/UnusedLocalVariable: <reason>",
                        "11:13 correctness/UnusedLocalVariable Local variable 'e' is never used",
                        "12:9 suppressions/UnusedSuppression Unused suppression: nothing matches lint/complexity here",
                        "13:13 correctness/UnusedLocalVariable Local variable 'g' is never used",
                        "14:9 suppressions/InvalidSuppression Unknown category \"nope\"; categories are [complexity, correctness, custom, internal, suppressions, syntax]"),
                t.findings(in));
    }

    @Test
    void wholeFileSuppression() {
        RuleTester t = RuleTester.of(new UnusedLocalVariable(), new PointlessBooleanExpression());
        assertEquals(
                List.of("2:41 complexity/PointlessBooleanExpression 'b == true' can be simplified to 'b'"),
                t.findings("// jpm-ignore-all lint/correctness/UnusedLocalVariable: legacy code\n"
                        + "class A { boolean b; void f() { int x = b == true ? 1 : 0; } }"));
    }

    @Test
    void suppressionOutlivesReformatting() {
        RuleTester t = RuleTester.withBuiltins();
        String in =
                "class A {\n    // jpm-ignore lint/correctness/UnusedLocalVariable: demo\n    void f() { int x = 1; }\n}\n";
        String out = t.format(in);
        assertEquals(
                "class A {\n    // jpm-ignore lint/correctness/UnusedLocalVariable: demo\n    void f() {\n        int x = 1;\n    }\n}\n",
                out);
        assertEquals(List.of(), t.findings(out));
    }

    @Test
    void configFromManifestWithExtendsOverridesAndIgnore() throws IOException {
        write("shared/format.jsonc", """
                { "linter": { "rules": { "suspicious": { "EmptyCatchBlock": "error" } } } }
                """);
        write("project.jsonc", """
                {
                  "name": "demo",
                  // comments and trailing commas are fine
                  "extensions": { "format": {
                    "extends": ["shared/format.jsonc"],
                    "ignore": ["src/gen/**"],
                    "overrides": [ { "include": ["test/**"], "linter": { "rules": { "suspicious": { "EmptyCatchBlock": "off" } } } } ],
                  } },
                }
                """);
        String code = "class A { void f() { try {} catch (Exception e) {} } }\n";
        write("src/A.java", code);
        write("test/A.java", code);
        write("src/gen/G.java", code);
        Workspace ws = open();
        assertEquals(List.of(dir.resolve("src/A.java"), dir.resolve("test/A.java")), ws.files(List.of()));
        Lint ecb = ws.registry.rules.get("EmptyCatchBlock");
        assertEquals(Severity.ERROR, ws.config.settings("src/A.java").level(ecb, true));
        assertEquals(Severity.OFF, ws.config.settings("test/A.java").level(ecb, true));
        assertTrue(ws.config.ignored("src/gen/G.java"));
        assertTrue(ws.config.ignored("dist/x.java"));
    }

    @Test
    void separateConfigFileAndModuleConfigs() throws IOException {
        write("jpm-format.jsonc", "{ \"linter\": { \"recommended\": false } }");
        write("project.jsonc", "{ \"name\": \"root\", \"workspace\": [\"lib\"] }");
        write(
                "lib/project.jsonc",
                "{ \"name\": \"lib\", \"extensions\": { \"format\": { \"linter\": { \"recommended\": true } } } }");
        Workspace ws = open();
        Lint ecb = ws.registry.rules.get("EmptyCatchBlock");
        assertEquals(Severity.OFF, ws.config.settings("src/A.java").level(ecb, true));
        assertEquals(Severity.WARN, ws.config.settings("lib/src/A.java").level(ecb, true));
        assertEquals(Severity.ERROR, ws.config.settings("src/A.java").level(ws.registry.rules.get("JavaFormat"), true));
    }

    @Test
    void aFormatterThatFailsIsReportedInCheckMode() {
        Formatter broken = new Formatter() {
            @Override
            public String id() {
                return "Broken";
            }

            @Override
            public String format(Context ctx) {
                throw new IllegalStateException("no jars");
            }
        };
        List<String> found = RuleTester.of(broken).findings("class A {}\n");
        assertEquals(1, found.size(), found.toString());
        assertTrue(found.get(0).contains("internal/RuleCrash Broken failed: java.lang.IllegalStateException: no jars"), found.get(0));
    }

    @Test
    void formattersByName() throws IOException {
        Path root = Path.of("/p");
        assertNull(ExternalFormatter.parse("intellij", root, null));
        assertNull(ExternalFormatter.parse("palantir", root, null));
        assertEquals(
                new ExternalFormatter.Spec("palantir", "com.palantir.javaformat:palantir-java-format", "2.50.0", root, null),
                ExternalFormatter.parse("palantir@2.50.0", root, null));
        assertEquals(
                new ExternalFormatter.Spec("google-java-format", "com.google.googlejavaformat:google-java-format", "latest", root, null),
                ExternalFormatter.parse("google-java-format", root, null));
        assertEquals("^1.24", ExternalFormatter.parse("google-java-format@^1.24", root, null).version());
        assertTrue(assertThrows(FormatException.class, () -> ExternalFormatter.parse("eclipse-x", root, null))
                .getMessage()
                .contains("\"google-java-format[@<version>]\""));
        assertThrows(FormatException.class, () -> ExternalFormatter.parse("palantir@", root, null));
        assertEquals("4.32", ExternalFormatter.parse("eclipse@4.32", root, root.resolve("e.xml")).version());
        assertThrows(FormatException.class, () -> ExternalFormatter.parse("intellij", root, root.resolve("e.xml")));
        assertThrows(FormatException.class, () -> ExternalFormatter.parse("google-java-format", root, root.resolve("e.xml")));
        write("project.jsonc", "{ \"extensions\": { \"format\": { \"formatter\": { \"style\": \"google-java-format@1.24.0\" } } } }");
        Config.Settings s = open().config.settings("A.java");
        assertEquals("1.24.0", s.externalFormatter().version());
        assertFalse(s.intellijStyle());
    }

    @Test
    void formattersFromAUrlOrGit() throws IOException {
        Path root = Path.of("/p");
        assertEquals(new ExternalFormatter.Spec("git", "github:me/fmt", "*", root, null), ExternalFormatter.parse("github:me/fmt", root, null));
        assertEquals("^1.2", ExternalFormatter.parse("github:me/fmt@^1.2", root, null).version());
        assertEquals("#main", ExternalFormatter.parse("codeberg:me/fmt#main", root, null).version());
        assertEquals("github:me/repo/fmt", ExternalFormatter.parse("github:me/repo/fmt#main", root, null).key());
        ExternalFormatter.Spec jar = ExternalFormatter.parse("https://example.com/f.jar#sha256=" + "a".repeat(64), root, null);
        assertEquals(new ExternalFormatter.Spec("jar", "https://example.com/f.jar", "a".repeat(64), root, null), jar);
        assertNull(ExternalFormatter.parse("https://example.com/f.jar", root, null).version());
        assertThrows(FormatException.class, () -> ExternalFormatter.parse("https://example.com/f.zip", root, null));
        assertThrows(FormatException.class, () -> ExternalFormatter.parse("https://example.com/f.jar#sha256=xyz", root, null));
        assertThrows(FormatException.class, () -> ExternalFormatter.parse("github:me/fmt@", root, null));
        assertThrows(FormatException.class, () -> ExternalFormatter.parse("github:me/fmt", root, root.resolve("e.xml")));
    }

    @Test
    void aJarWithARuleSetFormats() throws Exception {
        Path src = write("fmt/up/Upper.java", """
                package up;

                import dev.jpm.format.api.Context;
                import dev.jpm.format.api.Formatter;
                import dev.jpm.format.api.RuleSet;
                import dev.jpm.format.api.Rules;

                public class Upper implements RuleSet, Formatter {
                    public void register(Rules rules) { rules.add(this); }
                    public String id() { return "Upper"; }
                    public String format(Context ctx) { return ctx.source().replace("class a", "class A"); }
                }
                """);
        Path classes = dir.resolve("fmt/classes");
        int code = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), "-cp",
                System.getProperty("java.class.path"), src.toString());
        assertEquals(0, code);
        Path jarFile = dir.resolve("fmt/upper.jar");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jarFile))) {
            out.putNextEntry(new java.util.jar.JarEntry("up/Upper.class"));
            out.write(Files.readAllBytes(classes.resolve("up/Upper.class")));
            out.putNextEntry(new java.util.jar.JarEntry("META-INF/services/dev.jpm.format.api.RuleSet"));
            out.write("up.Upper\n".getBytes(StandardCharsets.UTF_8));
        }
        String sha = Hashing.sha256(Files.readAllBytes(jarFile));
        write("project.jsonc", "{ \"extensions\": { \"format\": { \"formatter\": { \"style\": \"" + jarFile.toUri() + "#sha256=" + sha + "\" } } } }");
        Config.Settings s = open().config.settings("A.java");
        assertFalse(s.intellijStyle());
        FileContext ctx = new FileContext(new Source("A.java", "class a {}\n"), null, Severity.ERROR, s, null, new java.util.ArrayList<>());
        assertEquals("class A {}\n", ExternalFormatter.format(s.externalFormatter(), ctx));
        assertTrue(Files.isDirectory(dir.resolve(".cache/format/formatters")), "the jar is kept in the cache");
        write("project.jsonc", "{ \"extensions\": { \"format\": { \"formatter\": { \"style\": \"" + jarFile.toUri() + "#sha256=" + "0".repeat(64) + "\" } } } }");
        Config.Settings wrong = open().config.settings("A.java");
        FormatException e = assertThrows(FormatException.class, () -> ExternalFormatter.format(wrong.externalFormatter(), ctx));
        assertTrue(e.getMessage().contains("has SHA-256 " + sha), e.getMessage());
    }

    @Test
    void configErrors() throws IOException {
        write(
                "project.jsonc",
                "{ \"extensions\": { \"format\": { \"linter\": { \"rules\": { \"style\": { \"NoSuchRule\": \"warn\" } } } } } }");
        assertTrue(assertThrows(FormatException.class, this::open).getMessage().contains("no rule named NoSuchRule"));
        write(
                "project.jsonc",
                "{ \"extensions\": { \"format\": { \"linter\": { \"rules\": { \"style\": { \"EmptyCatchBlock\": \"warn\" } } } } } }");
        assertTrue(
                assertThrows(FormatException.class, this::open).getMessage().contains("is in category \"suspicious\""));
        write("project.jsonc", "{ \"extensions\": { \"format\": { \"formatter\": { \"tabs\": true } } } }");
        assertTrue(assertThrows(FormatException.class, this::open).getMessage().contains("unknown key \"tabs\""));
        write("project.jsonc", "{ \"extensions\": { \"format\": { \"formatter\": { \"lineWidth\": 5 } } } }");
        Workspace ws = open();
        assertTrue(assertThrows(FormatException.class, () -> ws.config.settings("A.java"))
                .getMessage()
                .contains("formatter.lineWidth must be from 20 to 1000, not 5"));
        write("project.jsonc", "{ \"extensions\": { \"format\": {} } }");
        write("jpm-format.jsonc", "{}");
        assertTrue(assertThrows(FormatException.class, this::open).getMessage().contains("keep one"));
    }

    @Test
    void ruleOptionsReachTheRule() {
        Lint withOptions = new Lint() {
            @Override
            public String id() {
                return "Opts";
            }

            @Override
            public void check(Context ctx) {
                ctx.report(0, 1, "max=" + ctx.options().get("max"), null);
            }
        };
        RuleTester t = RuleTester.of(withOptions)
                .config(
                        "{ \"linter\": { \"rules\": { \"custom\": { \"Opts\": { \"level\": \"error\", \"options\": { \"max\": 3 } } } } } }");
        List<Finding> fs = t.check("class A {}");
        assertEquals("max=3", fs.get(0).message());
        assertEquals(Severity.ERROR, fs.get(0).severity());
    }

    @Test
    void customRulesFromTheRulesFolder() throws IOException {
        write(
                "project.jsonc",
                "{ \"name\": \"demo\", \"extensions\": { \"format\": { \"linter\": { \"rules\": { \"custom\": { \"NoPrintln\": \"error\" } } } } } }");
        write("rules/NoPrintln.java", """
                import com.sun.source.tree.MemberSelectTree;
                import com.sun.source.util.TreeScanner;
                import dev.jpm.format.api.*;

                public class NoPrintln implements Lint {
                    public String id() { return "NoPrintln"; }
                    public void check(Context ctx) {
                        new TreeScanner<Void, Void>() {
                            @Override public Void visitMemberSelect(MemberSelectTree t, Void v) {
                                if (t.getIdentifier().contentEquals("println")) ctx.report(t, "No println");
                                return super.visitMemberSelect(t, v);
                            }
                        }.scan(ctx.tree(), null);
                    }
                }
                """);
        write("rules/Ordered.java", """
                package demo.rules;
                import dev.jpm.format.api.*;

                public class Ordered implements RuleSet {
                    public void register(Rules rules) {
                        rules.addBefore("JavaFormat", new Formatter() {
                            public String id() { return "Banner"; }
                            public String format(Context ctx) {
                                return ctx.source().startsWith("// banner") ? ctx.source() : "// banner\\n" + ctx.source();
                            }
                        });
                    }
                }
                """);
        Workspace ws = open();
        List<String> ids = ws.registry.rules.all().stream().map(Lint::id).toList();
        assertEquals(ids.indexOf("JavaFormat") - 1, ids.indexOf("Banner"));
        assertTrue(ids.contains("NoPrintln"));

        Engine.Result r =
                ws.engine.run("src/A.java", "class A { void f() { System.out.println(); } }", Engine.Mode.FORMAT);
        assertEquals("// banner\nclass A {\n    void f() {\n        System.out.println();\n    }\n}\n", r.output());
        assertEquals(
                List.of("4:9 custom/NoPrintln No println"),
                r.findings().stream().map(Finding::brief).toList());
        assertEquals(Severity.ERROR, r.findings().get(0).severity());
        assertTrue(Files.isDirectory(dir.resolve(".cache/format/rules")));
    }

    @Test
    void customRuleCompileErrorsAreReadable() throws IOException {
        write("rules/Bad.java", "public class Bad implements dev.jpm.format.api.Lint { }");
        FormatException e = assertThrows(FormatException.class, this::open);
        assertTrue(e.getMessage().startsWith("Could not compile the custom rules:\n  Bad.java:1:"), e.getMessage());
    }

    @Test
    void cacheSkipsUnchangedFiles() {
        Workspace ws = open();
        Cache c = Cache.open(dir, ws.cacheKey);
        Engine.Result r = ws.engine.run("A.java", "class A { void f() { int x; } }\n", Engine.Mode.CHECK);
        c.put("A.java", r.output(), false, r.findings());
        c.save();
        Cache again = Cache.open(dir, ws.cacheKey);
        Cache.Entry e = again.get("A.java", "class A { void f() { int x; } }\n");
        assertEquals(r.findings(), e.findings());
        assertNull(again.get("A.java", "class B {}\n"));
        assertNull(Cache.open(dir, "other-config").get("A.java", "class A { void f() { int x; } }\n"));
    }

    @Test
    void snapshotTests() throws IOException {
        Path in = write(
                "rules/test/Sample.java", "class Sample { void f() { int unusedX; if (b == true) {} } boolean b; }\n");
        Workspace ws = open();
        assertEquals(0, SnapshotTests.run(ws, null, false));
        assertEquals("""
                class Sample {
                    void f() {
                        int unusedX;
                        if (b == true) {
                        }
                    }

                    boolean b;
                }
                """, Files.readString(in.resolveSibling("Sample.expected.java")));
        String findings = Files.readString(in.resolveSibling("Sample.findings"));
        assertTrue(findings.contains("1:1 format/JavaFormat Code is not formatted\n"), findings);
        assertTrue(
                findings.contains("complexity/PointlessBooleanExpression 'b == true' can be simplified to 'b'\n"),
                findings);
        assertEquals(0, SnapshotTests.run(ws, null, false));
        Files.writeString(in.resolveSibling("Sample.expected.java"), "changed\n");
        PrintStream err = System.err;
        try {
            System.setErr(new PrintStream(new ByteArrayOutputStream()));
            assertEquals(1, SnapshotTests.run(ws, null, false));
            assertEquals(0, SnapshotTests.run(ws, null, true));
        } finally {
            System.setErr(err);
        }
        assertFalse(ws.files(List.of()).contains(in), "rule test inputs are not formatted");
    }

    @Test
    void reporters() {
        Workspace ws = open();
        Engine.Result r = ws.engine.run(
                "src/A.java", "class A { boolean b; boolean f() { return b == true; } }\n", Engine.Mode.CHECK);
        List<Engine.Result> rs = List.of(r);
        Reporter.Summary sum = new Reporter.Summary(1, 1, true, 5);

        ByteArrayOutputStream json = new ByteArrayOutputStream();
        Reporter.named("json").report(rs, sum, ws.registry, new PrintStream(json, true, StandardCharsets.UTF_8));
        Map<String, Object> parsed = Json.object(json.toString(StandardCharsets.UTF_8), "json");
        @SuppressWarnings("unchecked") List<Map<String, Object>> fs = (List<Map<String, Object>>) parsed.get("findings");
        assertEquals(r.findings(), fs.stream().map(Reporter::fromJson).toList());

        ByteArrayOutputStream sarif = new ByteArrayOutputStream();
        Reporter.named("sarif").report(rs, sum, ws.registry, new PrintStream(sarif, true, StandardCharsets.UTF_8));
        Map<String, Object> s = Json.object(sarif.toString(StandardCharsets.UTF_8), "sarif");
        assertEquals("2.1.0", s.get("version"));
        assertTrue(sarif.toString(StandardCharsets.UTF_8)
                .contains("\"ruleId\": \"complexity/PointlessBooleanExpression\""));

        ByteArrayOutputStream gh = new ByteArrayOutputStream();
        Reporter.named("github").report(rs, sum, ws.registry, new PrintStream(gh, true, StandardCharsets.UTF_8));
        assertTrue(
                gh.toString(StandardCharsets.UTF_8)
                        .contains(
                                "::warning file=src/A.java,line=1,col=43,endLine=1,endColumn=52,title=lint/complexity/PointlessBooleanExpression::'b == true' can be simplified to 'b'"),
                gh.toString(StandardCharsets.UTF_8));
        assertEquals("a%3Ab%2Cc%0Ad", Reporter.Github.prop("a:b,c\nd"));
        assertThrows(FormatException.class, () -> Reporter.named("xml"));
    }

    @Test
    void jsonc() {
        Object v = Json.parse("// c\n{ \"a\": [1, 2.5, true, null, \"x\\n\\u0041\",], /* b */ \"b\": {}, }", "t");
        assertEquals("{\"a\":[1,2.5,true,null,\"x\\nA\"],\"b\":{}}", Json.write(v));
        assertThrows(FormatException.class, () -> Json.parse("{\"a\" 1}", "t"));
    }

    @Test
    void languageServer() throws IOException {
        Files.writeString(dir.resolve("project.jsonc"), "{ \"name\": \"demo\" }");
        String uri = dir.resolve("src/L.java").toUri().toString();
        String text = "class L { void f() { if (b == true) {} } boolean b; }\n";
        StringBuilder in = new StringBuilder();
        for (String m : List.of(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":"
                        + Json.quote(dir.toUri().toString()) + "}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{\"textDocument\":{\"uri\":"
                        + Json.quote(uri) + ",\"languageId\":\"java\",\"version\":1,\"text\":" + Json.quote(text)
                        + "}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/formatting\",\"params\":{\"textDocument\":{\"uri\":"
                        + Json.quote(uri) + "}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/codeAction\",\"params\":{\"textDocument\":{\"uri\":"
                        + Json.quote(uri)
                        + "},\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":50}}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"nope\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"shutdown\"}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}")) {
            in.append("Content-Length: ")
                    .append(m.getBytes(StandardCharsets.UTF_8).length)
                    .append("\r\n\r\n")
                    .append(m);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = new Lsp(new ByteArrayInputStream(in.toString().getBytes(StandardCharsets.UTF_8)), out).run();
        assertEquals(0, code);
        String o = out.toString(StandardCharsets.UTF_8);
        assertTrue(o.contains("\"method\":\"textDocument/publishDiagnostics\""), o);
        assertTrue(o.contains("\"code\":\"complexity/PointlessBooleanExpression\""), o);
        assertTrue(
                o.contains(
                        "\"newText\":\"class L {\\n    void f() {\\n        if (b == true) {\\n        }\\n    }\\n\\n    boolean b;\\n}\\n\""),
                o);
        assertTrue(o.contains("\"title\":\"Simplify (PointlessBooleanExpression)\""), o);
        assertTrue(o.contains("\"id\":4,\"error\":{") && o.contains("-32601"), o);
    }
}
