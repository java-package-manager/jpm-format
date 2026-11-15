package dev.jpm.format.api;

/**
 * A rule that changes code: {@code jpm format} runs every formatter in order, each on the previous one's
 * output. Anything applied automatically, such as removing unused imports, is a formatter, so it must never
 * change what the code does.
 *
 * <p>{@link #check} comes built in: it passes if formatting the input returns the same input, and otherwise
 * reports each place the output differs, with the formatted text as the fix. So every formatter is also a
 * linter for {@code jpm format --check}. Override it only to give more specific messages.
 *
 * <p>Formatting must be idempotent: formatting the output again must return it unchanged.
 * {@code jpm format --debug} checks this for every formatter.
 */
public interface Formatter extends Lint {
    /** Returns the formatted source; returning {@code ctx.source()} means nothing to change. */
    String format(Context ctx);

    /** Unformatted code is an error unless the config says otherwise. */
    @Override
    default Severity severity() {
        return Severity.ERROR;
    }

    @Override
    default void check(Context ctx) {
        String in = ctx.source();
        String out = format(ctx);
        for (LineDiff.Hunk h : LineDiff.hunks(in, out)) {
            ctx.report(h.start(), h.end(), message(h), Fix.replace("Format", h.start(), h.end(), h.replacement()));
        }
    }

    private static String message(LineDiff.Hunk h) {
        if (h.replacement().isEmpty()) return "Code should be removed by the formatter";
        if (h.start() == h.end()) return "Code should be added by the formatter";
        return "Code is not formatted";
    }
}
