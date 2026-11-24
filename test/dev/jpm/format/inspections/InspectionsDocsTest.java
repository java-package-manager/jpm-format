package dev.jpm.format.inspections;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * docs/inspections.md is generated from the inspections' summaries; {@code UPDATE_DOCS=1 jpm test} rewrites it.
 */
class InspectionsDocsTest {
    private static final Path DOC = Path.of("docs/inspections.md");

    private static final Map<String, String> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("correctness", "Code that is wrong: it does not do what it says, or always fails.");
        CATEGORIES.put("suspicious", "Code that is probably wrong, or easily misread.");
        CATEGORIES.put("complexity", "Code that does nothing, or more than it needs to.");
        CATEGORIES.put("performance", "Code that does needless work.");
        CATEGORIES.put("style", "Code that says what it does in a roundabout way.");
    }

    static String generate() {
        List<Inspection> all = Inspections.all();
        StringBuilder sb = new StringBuilder();
        sb.append("# IntelliJ inspections\n\n");
        sb.append("""
                IntelliJ IDEA's Java inspections that are on by default, reimplemented on javac's syntax tree: %d of them.
                Each id is IntelliJ's short name, so `@SuppressWarnings("<id>")` and `//noinspection <id>` written for
                IntelliJ suppress it here too, as `// jpm-ignore lint/<category>/<id>: <reason>` does. They run with
                `linter.recommended` (the default) and are configured like the other rules, under
                `linter.rules.<category>.<id>` (see [rules.md](rules.md)).

                Some use types: they are more precise when jpm-format runs in a jpm project whose dependencies are
                installed, and those marked **needs types** run only then.

                Not reimplemented: inspections that need data-flow analysis (`ConstantValue`, `DataFlowIssue`,
                `UnusedAssignment`, `MismatchedCollectionQueryUpdate`), the whole project (`unused`, `SameParameterValue`,
                `UnusedReturnValue`, `EmptyMethod`), or the project's language level (the "can be replaced with" migrations
                such as `Convert2Lambda`, `PatternVariableCanBeUsed` or `EnhancedSwitchMigration`); and IntelliJ-specific
                ones (nullability annotations, `@Contract`, injected languages, IntelliJ's own APIs).
                """.formatted(all.size()));
        for (Map.Entry<String, String> cat : CATEGORIES.entrySet()) {
            List<Inspection> in = all.stream().filter(i -> i.category().equals(cat.getKey())).toList();
            if (in.isEmpty()) continue;
            sb.append("\n## ").append(cat.getKey()).append("\n\n").append(cat.getValue()).append("\n");
            for (Inspection i : in) {
                sb.append("\n### ").append(i.id()).append("\n\n").append(i.summary());
                if (i.types() == Inspection.Types.REQUIRED) sb.append(" **Needs types.**");
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    @Test
    void docsListEveryInspection() throws Exception {
        String want = generate();
        if (System.getenv("UPDATE_DOCS") != null) Files.writeString(DOC, want);
        for (Inspection i : Inspections.all()) {
            if (!CATEGORIES.containsKey(i.category()))
                throw new AssertionError(i.id() + ": unknown category " + i.category());
        }
        assertEquals(want, Files.exists(DOC) ? Files.readString(DOC) : "", "docs/inspections.md is stale: run UPDATE_DOCS=1 jpm test");
    }
}
