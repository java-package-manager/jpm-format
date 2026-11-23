package dev.jpm.format;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the comments in Java source, skipping string, text block and char literals.
 */
public final class Comments {
    /**
     * A comment at {@code [start, end)}; {@code line} is true for {@code //} comments.
     */
    public record Comment(int start, int end, String text, boolean line) {
        /**
         * The text without its delimiters, trimmed.
         */
        public String body() {
            String t = line ? text.substring(2) : text.substring(2, Math.max(2, text.length() - 2));
            return t.strip();
        }
    }

    private Comments() {
    }

    public static List<Comment> scan(String s) {
        List<Comment> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                int e = i;
                while (e < n && s.charAt(e) != '\n' && s.charAt(e) != '\r') e++;
                out.add(new Comment(i, e, s.substring(i, e), true));
                i = e;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                e = e < 0 ? n : e + 2;
                out.add(new Comment(i, e, s.substring(i, e), false));
                i = e;
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                i += 3;
                while (i < n && !s.startsWith("\"\"\"", i)) i += s.charAt(i) == '\\' ? 2 : 1;
                i += 3;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && s.charAt(i) != c && s.charAt(i) != '\n') i += s.charAt(i) == '\\' ? 2 : 1;
                i++;
            } else {
                i++;
            }
        }
        return out;
    }

    /**
     * {@code [start, end)} of every text block ({@code """ ... """}), whose content whitespace is significant.
     */
    public static List<int[]> textBlocks(String s) {
        List<int[]> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                int st = i;
                i += 3;
                while (i < n && !s.startsWith("\"\"\"", i)) i += s.charAt(i) == '\\' ? 2 : 1;
                i = Math.min(n, i + 3);
                out.add(new int[] {st, i});
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && s.charAt(i) != c && s.charAt(i) != '\n') i += s.charAt(i) == '\\' ? 2 : 1;
                i++;
            } else {
                i++;
            }
        }
        return out;
    }

    /**
     * True if only whitespace lies between {@code from} and {@code to}.
     */
    public static boolean blank(String s, int from, int to) {
        for (int k = from; k < to; k++) if (!Character.isWhitespace(s.charAt(k))) return false;
        return true;
    }
}
