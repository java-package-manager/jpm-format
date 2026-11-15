package dev.jpm.format;

import java.util.ArrayList;
import java.util.List;

/**
 * Code the formatter must leave alone: from {@code // @formatter:off} to {@code // @formatter:on} (as in
 * IntelliJ and Eclipse; to the end of the file without an "on"), and the element after
 * {@code // jpm-ignore format}.
 *
 * <p>After a formatter runs, each protected region in its output is replaced by the original text of the
 * same region. Regions are matched by their markers, which formatters keep, in order.
 */
final class Regions {
    private Regions() {
    }

    /** Quick test before looking for regions. */
    static boolean mayHave(String text) {
        return text.contains("@formatter:off") || text.contains("jpm-ignore format");
    }

    /** Protected {@code [start, end)} ranges, in order; empty if none or the source does not parse. */
    static List<int[]> find(Source src) {
        List<int[]> out = new ArrayList<>();
        if (!mayHave(src.text)) return out;
        List<Comments.Comment> cs = src.comments();
        boolean parsed = src.errors().isEmpty();
        int offAt = -1;
        for (Comments.Comment c : cs) {
            String b = c.body();
            if (offAt < 0 && b.startsWith("@formatter:off")) {
                offAt = c.end();
            } else if (offAt >= 0 && b.startsWith("@formatter:on")) {
                out.add(new int[] {offAt, c.start()});
                offAt = -1;
            } else if (offAt < 0 && isIgnoreFormat(b) && parsed) {
                int[] el = Elements.next(src, c.end());
                if (el != null) out.add(new int[] {c.end(), el[1]});
            }
        }
        if (offAt >= 0) out.add(new int[] {offAt, src.text.length()});
        return out;
    }

    static boolean isIgnoreFormat(String body) {
        return body.equals("jpm-ignore format")
                || body.startsWith("jpm-ignore format:")
                || body.startsWith("jpm-ignore format ");
    }

    /**
     * Puts the original text of every protected region back into {@code formatted}. If the regions cannot be
     * matched up (a formatter dropped a marker), returns the original unchanged.
     */
    static String restore(Source original, String formatted) {
        if (!mayHave(original.text)) return formatted;
        List<int[]> before = find(original);
        if (before.isEmpty()) return formatted;
        Source out = new Source(original.path, formatted);
        List<int[]> after = find(out);
        if (after.size() != before.size()) return original.text;
        StringBuilder sb = new StringBuilder(formatted);
        for (int k = before.size() - 1; k >= 0; k--) {
            int[] a = after.get(k), b = before.get(k);
            sb.replace(a[0], a[1], original.text.substring(b[0], b[1]));
        }
        return sb.toString();
    }
}
