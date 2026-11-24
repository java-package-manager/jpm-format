package dev.jpm.format.files;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * JSON and JSONC files with IntelliJ IDEA's default spacing and indents, but their line breaks kept: IntelliJ puts
 * every value of every object and array on a line of its own, which would turn compact files such as jpm's
 * {@code project.jsonc} inside out. A colon has a space after it and none before, a comma the same; a line is one
 * indent in from the line its object or array opens on, and a closing bracket that starts a line lines up with that
 * line. Inside braces and brackets on one line, a space stays where the file has one ({@code { "a": 1 }} and
 * {@code {"a": 1}} both stay), and empty ones become {@code {}} and {@code []}.
 *
 * <p>Comments ({@code //} and {@code /* *}{@code /}) stay where they are. Blank lines are kept, up to
 * {@code maxBlankLines} in a row. Text that does not lex as JSON, or whose brackets do not match, is left as it is.
 * Text has {@code \n} line ends.
 */
public final class JsonFormat {
    private enum Kind {
        STRING, WORD, PUNCT, LINE_COMMENT, BLOCK_COMMENT
    }

    private record Token(Kind kind, int start, int end, String text) {
        boolean comment() {
            return kind == Kind.LINE_COMMENT || kind == Kind.BLOCK_COMMENT;
        }

        boolean is(char c) {
            return kind == Kind.PUNCT && text.charAt(0) == c;
        }

        boolean opens() {
            return is('{') || is('[');
        }

        boolean closes() {
            return is('}') || is(']');
        }
    }

    private JsonFormat() {
    }

    public static String format(String text, int indent, int maxBlankLines) {
        List<Token> tokens = lex(text);
        if (tokens == null || !balanced(tokens)) return text;
        StringBuilder out = new StringBuilder(text.length());
        Deque<Integer> bases = new ArrayDeque<>();
        int line = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            Token prev = i == 0 ? null : tokens.get(i - 1);
            String gap = prev == null ? "" : text.substring(prev.end(), t.start());
            int newlines = prev == null ? 0 : Math.min(count(gap, '\n'), maxBlankLines + 1);
            if (prev != null && prev.kind() == Kind.LINE_COMMENT) newlines = Math.max(newlines, 1);
            if (t.closes() && !bases.isEmpty()) {
                int base = bases.pop();
                if (newlines > 0) line = base;
            } else if (newlines > 0) {
                line = bases.isEmpty() ? 0 : bases.peek() + indent;
            }
            if (prev != null && newlines > 0) {
                strip(out);
                out.append("\n".repeat(newlines)).append(" ".repeat(line));
            } else if (prev != null) {
                out.append(" ".repeat(space(prev, t, gap.length())));
            }
            int column = out.length() - out.lastIndexOf("\n") - 1;
            out.append(t.kind() == Kind.BLOCK_COMMENT ? shift(t.text(), column - column(text, t.start())) : t.text());
            if (t.opens()) bases.push(line);
        }
        strip(out);
        if (text.endsWith("\n") && !out.isEmpty()) out.append('\n');
        return out.toString();
    }

    private static int space(Token a, Token b, int original) {
        if (b.comment()) return original;
        if (b.is(',') || b.is(':')) return 0;
        if (a.is(',') || a.is(':')) return 1;
        if (a.opens() && b.closes()) return 0;
        return original > 0 ? 1 : 0;
    }

    private static List<Token> lex(String s) {
        List<Token> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            Kind kind;
            if (c == '"' || c == '\'') {
                i++;
                while (i < n && s.charAt(i) != c) {
                    if (s.charAt(i) == '\n') return null;
                    i += s.charAt(i) == '\\' ? 2 : 1;
                }
                if (i >= n) return null;
                i++;
                kind = Kind.STRING;
            } else if (s.startsWith("//", i)) {
                while (i < n && s.charAt(i) != '\n') i++;
                kind = Kind.LINE_COMMENT;
            } else if (s.startsWith("/*", i)) {
                int close = s.indexOf("*/", i + 2);
                if (close < 0) return null;
                i = close + 2;
                kind = Kind.BLOCK_COMMENT;
            } else if ("{}[]:,".indexOf(c) >= 0) {
                i++;
                kind = Kind.PUNCT;
            } else {
                while (i < n && !Character.isWhitespace(s.charAt(i)) && "{}[]:,\"'".indexOf(s.charAt(i)) < 0
                        && !s.startsWith("//", i) && !s.startsWith("/*", i)) {
                    i++;
                }
                kind = Kind.WORD;
            }
            String t = s.substring(start, i);
            out.add(new Token(kind, start, i, kind == Kind.LINE_COMMENT ? t.stripTrailing() : t));
        }
        return out;
    }

    private static boolean balanced(List<Token> tokens) {
        Deque<Character> open = new ArrayDeque<>();
        for (Token t : tokens) {
            if (t.opens()) open.push(t.is('{') ? '}' : ']');
            else if (t.closes() && (open.isEmpty() || open.pop() != t.text().charAt(0))) return false;
        }
        return open.isEmpty();
    }

    private static String shift(String comment, int delta) {
        if (delta == 0 || comment.indexOf('\n') < 0) return comment;
        String[] parts = comment.split("\n", -1);
        StringBuilder b = new StringBuilder(parts[0]);
        for (int k = 1; k < parts.length; k++) {
            String p = parts[k];
            int lead = p.length() - p.stripLeading().length();
            b.append('\n');
            if (!p.isBlank()) b.append(" ".repeat(Math.max(0, lead + delta))).append(p.stripLeading().stripTrailing());
        }
        return b.toString();
    }

    private static int column(String text, int offset) {
        return offset - text.lastIndexOf('\n', offset - 1) - 1;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int k = 0; k < s.length(); k++) if (s.charAt(k) == c) n++;
        return n;
    }

    private static void strip(StringBuilder out) {
        int k = out.length();
        while (k > 0 && (out.charAt(k - 1) == ' ' || out.charAt(k - 1) == '\t')) k--;
        out.setLength(k);
    }
}
