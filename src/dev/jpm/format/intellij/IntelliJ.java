package dev.jpm.format.intellij;

import dev.jpm.format.api.Rules;

/**
 * The IntelliJ rules, placed right after the layout engine ({@code JavaFormat}), in this order. Each one is one of
 * IntelliJ IDEA's default Java code style settings that the layout engine leaves to the rules after it.
 */
public final class IntelliJ {
    private IntelliJ() {
    }

    public static void register(Rules rules) {
        rules.addAfter("JavaFormat", new EmptyBodies());
        rules.addAfter(EmptyBodies.ID, new MemberBlankLines());
        rules.addAfter(MemberBlankLines.ID, new KeepBlankLines());
        rules.addAfter(KeepBlankLines.ID, new JavadocLayout());
        rules.addAfter(JavadocLayout.ID, new LineCommentSpacing());
        rules.addAfter(LineCommentSpacing.ID, new ImportBlankLines());
    }
}
