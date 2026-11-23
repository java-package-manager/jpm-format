package dev.jpm.format.rules;

import com.google.common.collect.Range;
import dev.jpm.format.Comments;
import dev.jpm.format.Exports;
import dev.jpm.format.ExternalFormatter;
import dev.jpm.format.FileContext;
import dev.jpm.format.FormatException;
import dev.jpm.format.Jdks;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Formatter;
import dev.jpm.format.palantir.java.FormatterException;
import dev.jpm.format.palantir.java.JavaFormatterOptions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The layout of the code: indentation, line breaks and spacing. The engine is jpm-format's fork of Palantir Java
 * Format ({@code dev.jpm.format.palantir}). In the IntelliJ style it keeps the input's line breaks and indents as
 * IntelliJ's default Java code style does (4-space indents, 8-space continuation indents); in the Palantir style it
 * chooses the line breaks itself, wrapping at the line width.
 */
public final class JavaFormat implements Formatter {
    public static final String ID = "JavaFormat";

    private static final Map<List<Integer>, dev.jpm.format.palantir.java.Formatter> FORMATTERS =
            new ConcurrentHashMap<>();

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
        if (external != null) return ExternalFormatter.format(external, ctx.source(), ctx.path());
        try {
            return tabs(fc, palantir(fc).formatSource(ctx.source()));
        } catch (FormatterException e) {
            throw new FormatException(ctx.path() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Formats only the lines touching {@code [start, end)}.
     */
    public String formatRange(FileContext ctx, int start, int end) {
        if (ctx.settings().externalFormatter() != null) return format(ctx);
        try {
            return tabs(
                    ctx,
                    palantir(ctx)
                            .formatSource(ctx.source(), List.of(Range.closedOpen(start, Math.max(start + 1, end)))));
        } catch (FormatterException e) {
            throw new FormatException(ctx.path() + ": " + e.getMessage(), e);
        }
    }

    private static dev.jpm.format.palantir.java.Formatter palantir(FileContext ctx) {
        if (!Jdks.formatterSupported()) throw new FormatException(Jdks.unsupportedMessage());
        if (!Exports.open()) {
            throw new FormatException(
                    "the formatter needs javac's internals; run the JVM with " + String.join(" ", Exports.flags()));
        }
        var s = ctx.settings();
        return FORMATTERS.computeIfAbsent(
                List.of(s.lineWidth(), s.indentWidth(), s.continuationIndentWidth(), s.intellijStyle() ? 1 : 0),
                k -> dev.jpm.format.palantir.java.Formatter.createFormatter(JavaFormatterOptions.builder()
                        .style(JavaFormatterOptions.Style.PALANTIR)
                        .maxLineLength(s.lineWidth())
                        .indent(s.indentWidth())
                        .continuationIndent(s.continuationIndentWidth())
                        .formatJavadoc(false)
                        .keepLineBreaks(s.intellijStyle())
                        .build()));
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
