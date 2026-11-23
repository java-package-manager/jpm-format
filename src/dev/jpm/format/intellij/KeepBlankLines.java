package dev.jpm.format.intellij;

import dev.jpm.format.FileContext;
import dev.jpm.format.Source;
import dev.jpm.format.api.Context;

/**
 * IntelliJ's "Keep maximum blank lines" (in declarations, in code and before {@code '}'}), 2 by default: blank
 * lines the code already had stay, up to {@code formatter.maxBlankLines}. Palantir Java Format keeps at most one
 * and none before a closing brace.
 *
 * <p>The layout engine only changes whitespace, so its input and output have the same non-whitespace text. Walking
 * both side by side lines up every gap between two characters; where the output still breaks the line but has
 * fewer blank lines than the input had, they are put back. Gaps the layout engine joined into one line stay joined.
 */
public final class KeepBlankLines implements IntelliJRule {
    public static final String ID = "KeepBlankLines";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String format(Context ctx) {
        FileContext fc = (FileContext) ctx;
        String out = ctx.source();
        String in = fc.layoutInput().text;
        if (in.equals(out)) return out;
        int max = fc.settings().maxBlankLines();
        String nl = Source.newline(out);
        StringBuilder sb = new StringBuilder(out.length() + 64);
        int i = 0, j = 0;
        boolean first = true;
        while (true) {
            int i2 = skipSpace(in, i), j2 = skipSpace(out, j);
            boolean end = i2 >= in.length() || j2 >= out.length();
            if (end && (i2 < in.length() || j2 < out.length())) return out;
            String gap = out.substring(j, j2);
            if (!first && !end) gap = gap(in.substring(i, i2), gap, max, nl);
            sb.append(gap);
            if (end) return sb.toString();
            if (in.charAt(i2) != out.charAt(j2)) return out;
            sb.append(out.charAt(j2));
            i = i2 + 1;
            j = j2 + 1;
            first = false;
        }
    }

    /**
     * The output's gap, with the blank lines of the input's gap put back, up to {@code max}.
     */
    static String gap(String in, String out, int max, String nl) {
        int lineBreaksOut = count(out), lineBreaksIn = count(in);
        if (lineBreaksOut == 0 || lineBreaksIn <= lineBreaksOut) return out;
        int want = Math.min(lineBreaksIn, max + 1);
        if (want <= lineBreaksOut) return out;
        String indent = out.substring(out.lastIndexOf('\n') + 1);
        return nl.repeat(want) + indent;
    }

    private static int count(String s) {
        int n = 0;
        for (int k = 0; k < s.length(); k++) if (s.charAt(k) == '\n') n++;
        return n;
    }

    private static int skipSpace(String s, int k) {
        while (k < s.length() && Character.isWhitespace(s.charAt(k))) k++;
        return k;
    }
}
