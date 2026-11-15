package dev.jpm.format.rules;

import dev.jpm.format.api.Rules;

/**
 * The built-in rules, compiled into jpm-format. Formatters run in this order: license header, unused imports,
 * redundant modifiers, import order, then the layout itself last.
 */
public final class Builtins {
    static final String DOCS = "https://github.com/java-package-manager/jpm-format/blob/main/docs/rules.md#";

    private Builtins() {}

    public static void register(Rules rules) {
        rules.add(new LicenseHeader());
        rules.add(new UnusedImport());
        rules.add(new UnnecessaryModifier());
        rules.add(new ImportOrder());
        rules.add(new JavaFormat());

        rules.add(new MissingOverrideAnnotation());
        rules.add(new StringEquality());
        rules.add(new PointlessBooleanExpression());
        rules.add(new EmptyCatchBlock());
        rules.add(new UnusedLocalVariable());
        rules.add(new UnusedPrivateMember());
    }

    static String docs(String id) {
        return DOCS + id.toLowerCase(java.util.Locale.ROOT);
    }
}
