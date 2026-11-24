package dev.jpm.format.files;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * TOML files, such as Gradle's {@code libs.versions.toml}, as IntelliJ IDEA's default code style formats them: no
 * whitespace before a key or a table header, one space around {@code =}, {@code { a = 1, b = 2 }} for inline tables,
 * and the lines of an array that goes on over several lines one indent in per level. Line breaks, blank lines (up to
 * {@code maxBlankLines}), the space before a comment and strings, multi-line ones included, stay as they are. Text that
 * does not lex as TOML, or whose brackets do not match, is left as it is. Text has {@code \n} line ends.
 */
public final class TomlFormat {
    private enum Kind {WORD, STRING, PUNCT, COMMENT}

    private record Token(Kind kind, int start, int end, String text) {
        boolean is(char c) {
            return kind == Kind.PUNCT && text.charAt(0) == c;
        }
    }

    private TomlFormat() {
    }

    public static String format(String text, int indent, int maxBlankLines) {
        List<Token> tokens = lex(text);
        if (tokens == null) return text;
        StringBuilder out = new StringBuilder(text.length());
        Deque<Character> open = new ArrayDeque<>();
        Deque<Boolean> header = new ArrayDeque<>();
        int arrays = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            Token prev = i == 0 ? null : tokens.get(i - 1);
            String gap = prev == null ? "" : text.substring(prev.end(), t.start());
            int newlines = Math.min((int) gap.chars().filter(c -> c == '\n').count(), maxBlankLines + 1);
            boolean lineStart = prev == null || newlines > 0;
            boolean closes = t.is(']') || t.is('}');
            if (closes) {
                if (open.isEmpty() || open.peek() != (t.is(']') ? '[' : '{')) return text;
                open.pop();
                if (!header.pop() && t.is(']')) arrays--;
            }
            if (prev != null && lineStart) {
                strip(out);
                out.append("\n".repeat(newlines)).append(" ".repeat(arrays * indent));
            } else if (prev != null) {
                out.append(" ".repeat(space(prev, t, gap.length(), header.isEmpty() ? null : header.peek(),
                        open.isEmpty() ? 0 : open.peek())));
            }
            out.append(t.kind() == Kind.COMMENT ? t.text().stripTrailing() : t.text());
            if (t.is('[') || t.is('{')) {
                boolean isHeader = t.is('[') && (lineStart && open.isEmpty()
                        || prev != null && prev.is('[') && Boolean.TRUE.equals(header.peek()));
                open.push(t.is('[') ? '[' : '{');
                header.push(isHeader);
                if (!isHeader && t.is('[')) arrays++;
            }
        }
        if (!open.isEmpty()) return text;
        strip(out);
        if (text.endsWith("\n") && !out.isEmpty()) out.append('\n');
        return out.toString();
    }

    private static int space(Token a, Token b, int original, Boolean header, char inside) {
        if (b.kind() == Kind.COMMENT) return Math.max(1, original);
        if (Boolean.TRUE.equals(header) || a.is('[') && b.is('[') || a.is(']') && b.is(']')) return 0;
        if (a.is('=') || b.is('=')) return 1;
        if (b.is(',')) return 0;
        if (a.is(',')) return 1;
        if (a.is('{')) return b.is('}') ? 0 : 1;
        if (b.is('}')) return 1;
        if (a.is('[') || b.is(']')) return inside == '[' && original > 0 ? 1 : 0;
        return original > 0 ? 1 : 0;
    }

    private static List<Token> lex(String s) {
        List<Token> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
                continue;
            }
            int start = i;
            Kind kind;
            if (c == '#') {
                while (i < n && s.charAt(i) != '\n') i++;
                kind = Kind.COMMENT;
            } else if (s.startsWith("\"\"\"", i) || s.startsWith("'''", i)) {
                String q = s.substring(i, i + 3);
                int k = i + 3;
                while (k < n && !s.startsWith(q, k)) k += c == '"' && s.charAt(k) == '\\' ? 2 : 1;
                if (k >= n) return null;
                k += 3;
                while (k < n && s.charAt(k) == c) k++;
                i = k;
                kind = Kind.STRING;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && s.charAt(i) != c) {
                    if (s.charAt(i) == '\n') return null;
                    i += c == '"' && s.charAt(i) == '\\' ? 2 : 1;
                }
                if (i >= n) return null;
                i++;
                kind = Kind.STRING;
            } else if ("[]{}=,".indexOf(c) >= 0) {
                i++;
                kind = Kind.PUNCT;
            } else {
                while (i < n && " \t\r\n#\"'[]{}=,".indexOf(s.charAt(i)) < 0) i++;
                kind = Kind.WORD;
            }
            out.add(new Token(kind, start, i, s.substring(start, i)));
        }
        return out;
    }

    private static void strip(StringBuilder out) {
        int k = out.length();
        while (k > 0 && (out.charAt(k - 1) == ' ' || out.charAt(k - 1) == '\t')) k--;
        out.setLength(k);
    }
}
