package dev.jpm.format.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A change that resolves a finding: text edits on the file's source. Fixes on plain lint findings are only
 * offered (in the language server and the JSON and SARIF reports), never applied by {@code jpm format}.
 */
public record Fix(String description, List<Edit> edits) {
    /** Replaces {@code [start, end)} with {@code replacement}. */
    public record Edit(int start, int end, String replacement) {
        public Edit {
            if (start < 0 || end < start) throw new IllegalArgumentException("bad edit range " + start + ".." + end);
        }
    }

    public Fix {
        edits = List.copyOf(edits);
    }

    public static Fix replace(String description, int start, int end, String replacement) {
        return new Fix(description, List.of(new Edit(start, end, replacement)));
    }

    public static Fix insert(String description, int at, String text) {
        return replace(description, at, at, text);
    }

    public static Fix delete(String description, int start, int end) {
        return replace(description, start, end, "");
    }

    /** Applies the edits to {@code source}. Edits must not overlap. */
    public String apply(String source) {
        return apply(source, edits);
    }

    /** Applies non-overlapping edits to {@code source}; overlapping ones after the first are skipped. */
    public static String apply(String source, List<Edit> edits) {
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
        StringBuilder sb = new StringBuilder(source.length());
        int pos = 0;
        for (Edit e : sorted) {
            if (e.start() < pos || e.end() > source.length()) continue;
            sb.append(source, pos, e.start()).append(e.replacement());
            pos = e.end();
        }
        return sb.append(source, pos, source.length()).toString();
    }
}
