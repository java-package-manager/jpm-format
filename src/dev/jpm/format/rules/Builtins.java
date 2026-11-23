package dev.jpm.format.rules;

import dev.jpm.format.api.Rules;
import dev.jpm.format.inspections.Inspection;
import dev.jpm.format.inspections.Inspections;
import dev.jpm.format.intellij.IntelliJ;

/**
 * The built-in rules, compiled into jpm-format. Formatters run in this order: license header, unused imports,
 * redundant modifiers, import order, the layout itself, then the IntelliJ adjustments to it.
 */
public final class Builtins {
    static final String DOCS = "https://github.com/java-package-manager/jpm-format/blob/main/docs/rules.md#";

    private Builtins() {
    }

    public static void register(Rules rules) {
        rules.add(new LicenseHeader());
        rules.add(new UnusedImport());
        rules.add(new UnnecessaryModifier());
        rules.add(new ImportOrder());
        rules.add(new JavaFormat());
        IntelliJ.register(rules);

        rules.add(new MissingOverrideAnnotation());
        rules.add(new NonJavadocComment());
        rules.add(new StringEquality());
        rules.add(new PointlessBooleanExpression());
        rules.add(new EmptyCatchBlock());
        rules.add(new UnusedLocalVariable());
        rules.add(new UnusedPrivateMember());
        for (Inspection i : Inspections.all()) rules.add(i);
    }

    static String docs(String id) {
        return DOCS + id.toLowerCase(java.util.Locale.ROOT);
    }
}
