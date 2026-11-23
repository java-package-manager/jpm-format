package dev.jpm.format.rules;

import dev.jpm.format.Comments;
import dev.jpm.format.FileContext;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Fix;
import dev.jpm.format.api.Lint;
import dev.jpm.format.api.Severity;

/**
 * A {@code //} or {@code /* *}{@code /} comment, for projects that keep decisions in documents and explanations in
 * Javadoc. Off by default. Javadoc, suppression comments ({@code jpm-ignore}, {@code noinspection}) and comment
 * markers in strings, character literals and text blocks are left alone. The fix removes the comment: one on a
 * line of its own goes with its line, a trailing one with the space before it. It is offered, never applied.
 */
public final class NonJavadocComment implements Lint {
    @Override
    public String id() {
        return "NonJavadocComment";
    }

    @Override
    public String category() {
        return "style";
    }

    @Override
    public Severity severity() {
        return Severity.OFF;
    }

    @Override
    public String docs() {
        return Builtins.docs(id());
    }

    @Override
    public void check(Context ctx) {
        String s = ctx.source();
        for (Comments.Comment c : ((FileContext) ctx).parsed().comments()) {
            if (javadoc(c) || suppression(c)) continue;
            ctx.report(c.start(), c.end(), "Comment that is not Javadoc", removal(s, c));
        }
    }

    static boolean javadoc(Comments.Comment c) {
        return !c.line() && c.text().startsWith("/**") && !c.text().startsWith("/**/");
    }

    private static boolean suppression(Comments.Comment c) {
        String b = c.body();
        return b.startsWith("jpm-ignore") || b.startsWith("noinspection");
    }

    static Fix removal(String s, Comments.Comment c) {
        int start = c.start();
        while (start > 0 && (s.charAt(start - 1) == ' ' || s.charAt(start - 1) == '\t')) start--;
        int end = c.end();
        boolean ownLine = start == 0 || s.charAt(start - 1) == '\n';
        if (ownLine) {
            int e = end;
            while (e < s.length() && (s.charAt(e) == ' ' || s.charAt(e) == '\t' || s.charAt(e) == '\r')) e++;
            if (e < s.length() && s.charAt(e) == '\n') end = e + 1;
            else if (e == s.length()) end = e;
            else start = c.start();
        }
        return Fix.delete("Remove the comment", start, end);
    }
}
