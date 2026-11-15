package dev.jpm.format.api;

import dev.jpm.format.Config;
import dev.jpm.format.Engine;
import dev.jpm.format.Json;
import dev.jpm.format.Project;
import dev.jpm.format.Registry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests rules from JUnit (or any test framework): give an input and the expected output or findings.
 *
 * <pre>
 * RuleTester t = RuleTester.of(new MyRule());
 * t.assertFindings("class A { void f() { System.out.println(); } }",
 *         "1:22 custom/MyRule Do not print to stdout");
 * t.assertFormats("class A {int x;}", "class A {\n    int x;\n}\n");
 * </pre>
 *
 * Only the given rules run (plus the suppression checks), with their default severity unless
 * {@link #config(String)} says otherwise. {@link #withTypes()} gives type-aware rules the JDK's types.
 * For whole-file snapshots of every rule, see {@code jpm format test}.
 */
public final class RuleTester {
    private final Registry registry;
    private String config = "{}";
    private boolean types;
    private String path;

    private RuleTester(Registry registry) {
        this.registry = registry;
    }

    /** A tester for exactly these rules, in this order. */
    public static RuleTester of(Lint... rules) {
        return new RuleTester(Registry.of(List.of(rules)));
    }

    /** A tester for the built-in rules and these, as {@code jpm format} runs them. */
    public static RuleTester withBuiltins(Lint... rules) {
        Registry r = Registry.builtinsAnd(List.of(rules));
        return new RuleTester(r);
    }

    /** The format config to test with, as in {@code "extensions": {"format": ...}}. */
    public RuleTester config(String jsonc) {
        this.config = jsonc;
        return this;
    }

    /** Gives type-aware rules type information, resolved against the JDK only. */
    public RuleTester withTypes() {
        this.types = true;
        return this;
    }

    /** The file path rules see; by default the name of the first class in the input. */
    public RuleTester path(String path) {
        this.path = path;
        return this;
    }

    private Engine engine() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "jpm-format-rule-tester")
                .toAbsolutePath();
        Project project = types
                ? new Project(root, List.of(new Project.Module(root, List.of(), List.of())), null)
                : Project.untyped(root, "RuleTester without withTypes()");
        Map<String, Object> cfg = Json.object(config, "RuleTester config");
        return new Engine(project, Config.of(root, cfg, registry.rules), registry, true, w -> {});
    }

    private String pathFor(String source) {
        if (path != null) return path;
        Matcher m = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)")
                .matcher(source);
        return (m.find() ? m.group(1) : "Test") + ".java";
    }

    /** Runs the formatters in order and returns the result. */
    public String format(String source) {
        return engine().run(pathFor(source), source, Engine.Mode.FORMAT).output();
    }

    /** Runs every rule's check, as {@code jpm format --check} does. */
    public List<Finding> check(String source) {
        return engine().run(pathFor(source), source, Engine.Mode.CHECK).findings();
    }

    /** {@link #check} as {@link Finding#brief()} strings: {@code line:column category/Rule message}. */
    public List<String> findings(String source) {
        List<String> out = new ArrayList<>();
        for (Finding f : check(source)) out.add(f.brief());
        return out;
    }

    /** Fails unless formatting {@code input} gives {@code expected}, and formatting that again changes nothing. */
    public void assertFormats(String input, String expected) {
        String got = format(input);
        if (!got.equals(expected)) {
            throw new AssertionError("Formatting gave:\n" + got + "\nbut expected:\n" + expected);
        }
        String again = format(got);
        if (!again.equals(got))
            throw new AssertionError("Formatting is not idempotent; the second pass gave:\n" + again);
    }

    /** Fails unless {@code check(input)} reports exactly these findings, as {@link Finding#brief()} strings. */
    public void assertFindings(String input, String... expected) {
        List<String> got = findings(input);
        if (!got.equals(List.of(expected))) {
            throw new AssertionError("Findings were:\n  " + String.join("\n  ", got) + "\nbut expected:\n  "
                    + String.join("\n  ", expected));
        }
    }
}
