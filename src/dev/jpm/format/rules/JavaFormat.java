package dev.jpm.format.rules;

import dev.jpm.format.Comments;
import dev.jpm.format.ExternalFormatter;
import dev.jpm.format.FileContext;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Formatter;
import dev.jpm.format.intellij.Layout;

import java.util.List;

/**
 * The layout of the code: indentation, line breaks and spacing. In the default IntelliJ style the engine is
 * jpm-format's own ({@link Layout}, ADR 0006): IntelliJ's default Java code style, with the input's line breaks kept
 * where IntelliJ keeps them. The other styles ({@code palantir}, {@code google-java-format}, {@code eclipse} and
 * formatters from a jar or a Git repository) run the formatter itself ({@link ExternalFormatter}).
 */
public final class JavaFormat implements Formatter {
    public static final String ID = "JavaFormat";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String category() {
        return "format";
    }

    @Override
    public String docs() {
        return Builtins.docs(ID);
    }

    @Override
    public String format(Context ctx) {
        FileContext fc = (FileContext) ctx;
        var external = fc.settings().externalFormatter();
        if (external != null) return ExternalFormatter.format(external, fc);
        return tabs(fc, Layout.format(fc));
    }

    /**
     * Formats only the lines touching {@code [start, end)}; other styles than IntelliJ's format the whole file.
     */
    public String formatRange(FileContext ctx, int start, int end) {
        if (ctx.settings().externalFormatter() != null) return format(ctx);
        return tabs(ctx, Layout.format(ctx, start, Math.max(start + 1, end)));
    }

    /**
     * With {@code useTabs}, turns leading indentation into tabs, except inside text blocks.
     */
    private static String tabs(FileContext ctx, String text) {
        if (!ctx.settings().useTabs()) return text;
        int width = ctx.settings().indentWidth();
        List<int[]> blocks = Comments.textBlocks(text);
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int eol = text.indexOf('\n', i);
            int end = eol < 0 ? text.length() : eol + 1;
            int at = i;
            boolean inBlock = blocks.stream().anyMatch(b -> at > b[0] && at < b[1]);
            int sp = i;
            while (sp < end && text.charAt(sp) == ' ') sp++;
            int n = sp - i;
            if (!inBlock && n > 0) {
                sb.append("\t".repeat(n / width)).append(" ".repeat(n % width));
                sb.append(text, sp, end);
            } else {
                sb.append(text, i, end);
            }
            i = end;
        }
        return sb.toString();
    }
}
