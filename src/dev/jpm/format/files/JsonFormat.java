package dev.jpm.format.files;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * JSON and JSONC files with IntelliJ IDEA's default spacing and indents, but their line breaks kept: IntelliJ puts
 * every value of every object and array on a line of its own, which would turn compact files such as jpm's
 * {@code project.jsonc} inside out. A colon has a space after it and none before, a comma the same; a line is one
 * indent in from the line its object or array opens on, and a closing bracket that starts a line lines up with that
 * line. Inside braces and brackets on one line, a space stays where the file has one ({@code { "a": 1 }} and
 * {@code {"a": 1}} both stay), and empty ones become {@code {}} and {@code []}. A line that would end past the line
 * width is split as IntelliJ's "chop down if long" does: the outermost object or array that starts or separates
 * elements on it gets every element on a line of its own and its closing bracket too, and the lines that makes are
 * measured again.
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
        return format(text, indent, maxBlankLines, Integer.MAX_VALUE);
    }

    /**
     * The file with its line breaks kept, and the objects and arrays split that make a line end past {@code width}.
     * {@code owner} is the object or array a token is in, for a closing bracket the one it closes. Each pass splits
     * the outermost object or array of every line past the width that is not inside another one split in the same
     * pass, so a file takes about as many passes as it nests, not one per object.
     */
    public static String format(String text, int indent, int maxBlankLines, int width) {
        List<Token> tokens = lex(text);
        if (tokens == null || !balanced(tokens)) return text;
        int n = tokens.size();
        int[] close = new int[n];
        int[] owner = new int[n];
        int[] depth = new int[n];
        Deque<Integer> open = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            Token t = tokens.get(i);
            depth[i] = open.size();
            if (t.closes()) {
                int o = open.pop();
                close[o] = i;
                owner[i] = o;
                continue;
            }
            owner[i] = open.isEmpty() ? -1 : open.peek();
            if (t.opens()) open.push(i);
        }
        boolean[] chopped = new boolean[n];
        int[] lineOf = new int[n];
        while (true) {
            String out = keep(text, tokens, close, owner, chopped, lineOf, indent, maxBlankLines);
            String[] lines = out.split("\n", -1);
            int[] best = new int[lines.length];
            Arrays.fill(best, -1);
            for (int i = 0; i + 1 < n; i++) {
                Token t = tokens.get(i);
                int row = lineOf[i];
                if (lines[row].length() <= width || lineOf[i + 1] != row) continue;
                int c = t.opens() && close[i] != i + 1 ? i : t.is(',') ? owner[i] : -1;
                if (c < 0 || chopped[c] || tokens.get(i + 1).closes() && close[c] == i + 1) continue;
                if (best[row] < 0 || depth[c] < depth[best[row]]) best[row] = c;
            }
            boolean[] chosen = new boolean[n];
            for (int c : best) if (c >= 0) chosen[c] = true;
            boolean changed = false;
            for (int c : best) {
                if (c < 0 || chopped[c]) continue;
                boolean nested = false;
                for (int a = owner[c]; a >= 0 && !nested; a = owner[a]) nested = chosen[a];
                if (nested) continue;
                chopped[c] = true;
                changed = true;
            }
            if (!changed) return out;
        }
    }

    private static String keep(String text, List<Token> tokens, int[] close, int[] owner, boolean[] chopped,
                               int[] lineOf, int indent, int maxBlankLines) {
        StringBuilder out = new StringBuilder(text.length());
        Deque<Integer> bases = new ArrayDeque<>();
        int line = 0;
        int row = 0;
        boolean pending = false;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            Token prev = i == 0 ? null : tokens.get(i - 1);
            String gap = prev == null ? "" : text.substring(prev.end(), t.start());
            int newlines = prev == null ? 0 : Math.min(count(gap, '\n'), maxBlankLines + 1);
            if (prev != null && prev.kind() == Kind.LINE_COMMENT) newlines = Math.max(newlines, 1);
            boolean trailing = t.comment() && newlines == 0;
            if (pending && !trailing) newlines = Math.max(newlines, 1);
            if (t.closes() && owner[i] >= 0 && chopped[owner[i]] && close[owner[i]] != owner[i] + 1) {
                newlines = Math.max(newlines, 1);
            }
            if (t.closes() && !bases.isEmpty()) {
                int base = bases.pop();
                if (newlines > 0) line = base;
            } else if (newlines > 0) {
                line = bases.isEmpty() ? 0 : bases.peek() + indent;
            }
            if (prev != null && newlines > 0) {
                strip(out);
                out.append("\n".repeat(newlines)).append(" ".repeat(line));
                row += newlines;
            } else if (prev != null) {
                out.append(" ".repeat(space(prev, t, gap.length())));
            }
            lineOf[i] = row;
            int column = out.length() - out.lastIndexOf("\n") - 1;
            String written = t.kind() == Kind.BLOCK_COMMENT ? shift(t.text(), column - column(text, t.start())) : t.text();
            out.append(written);
            row += count(written, '\n');
            if (t.opens()) bases.push(line);
            if (t.opens() && chopped[i] && close[i] != i + 1 || t.is(',') && owner[i] >= 0 && chopped[owner[i]]) {
                pending = true;
            } else if (!t.comment()) {
                pending = false;
            }
        }
        strip(out);
        if (text.endsWith("\n") && !out.isEmpty()) out.append('\n');
        return out.toString();
    }

    /**
     * The file in a style: {@code "keep"} ({@link #format(String, int, int)}), {@code "pretty"} (every value of a
     * non-empty object or array on a line of its own, as a pretty printer writes it) or {@code "compact"} (an object
     * or array that fits on its line within {@code width} stays on it, written {@code { "a": 1 }} and
     * {@code [1, 2]}; the others are written as in {@code "pretty"}). Both rewrite the line breaks, without blank
     * lines; an object or array with a comment in it is never put on one line, and a comment stays at the end of the
     * line it ends.
     */
    public static String format(String text, String style, int indent, int maxBlankLines, int width) {
        if (style.equals("keep")) return format(text, indent, maxBlankLines, width);
        List<Token> tokens = lex(text);
        if (tokens == null || !balanced(tokens)) return text;
        boolean pretty = style.equals("pretty");
        int n = tokens.size();
        int[] close = new int[n];
        int[] inline = new int[n];
        Deque<Integer> open = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (tokens.get(i).opens()) open.push(i);
            else if (tokens.get(i).closes()) close[open.pop()] = i;
        }
        for (int i = 0; i < n; i++) if (tokens.get(i).opens()) inline[i] = inlineLength(tokens, i, close[i]);
        StringBuilder out = new StringBuilder(text.length());
        Deque<int[]> frames = new ArrayDeque<>();
        int line = 0;
        boolean element = false;
        for (int i = 0; i < n; i++) {
            Token t = tokens.get(i);
            Token prev = i == 0 ? null : tokens.get(i - 1);
            int[] f = frames.peek();
            boolean expanded = f != null && f[1] == 1;
            boolean closing = t.closes() && f != null;
            boolean sameLine = prev != null && count(text.substring(prev.end(), t.start()), '\n') == 0;
            if (prev == null) {
                line = 0;
            } else if (t.comment() && sameLine && prev.kind() != Kind.LINE_COMMENT) {
                out.append(' ');
            } else if (prev.kind() == Kind.LINE_COMMENT || t.comment() || closing && expanded
                    || expanded && element) {
                line = f == null ? 0 : closing ? f[0] : f[0] + indent;
                strip(out);
                out.append('\n').append(" ".repeat(line));
            } else {
                out.append(" ".repeat(inlineSpace(prev, t, f)));
            }
            if (!t.comment()) element = false;
            out.append(t.text());
            if (t.opens()) {
                int column = out.length() - out.lastIndexOf("\n") - 1 - 1;
                boolean empty = close[i] == i + 1;
                boolean fits = !pretty && inline[i] >= 0 && column + inline[i] <= width;
                frames.push(new int[]{line, empty || fits ? 0 : 1, t.is('{') ? 1 : 0});
                element = !empty && !fits;
            } else if (t.closes() && f != null) {
                frames.pop();
            } else if (t.is(',') && expanded) {
                element = true;
            }
        }
        strip(out);
        if (text.endsWith("\n") && !out.isEmpty()) out.append('\n');
        return out.toString();
    }

    private static int inlineSpace(Token a, Token b, int[] frame) {
        if (b.is(',') || b.is(':')) return 0;
        if (a.is(',') || a.is(':')) return 1;
        if (a.opens() && b.closes()) return 0;
        boolean object = frame != null && frame[2] == 1;
        if (a.is('{') || b.is('}') && object) return 1;
        if (a.is('[') || b.is(']')) return 0;
        return 1;
    }

    private static int inlineLength(List<Token> tokens, int open, int close) {
        if (close == open + 1) return 2;
        int length = 0;
        for (int k = open; k <= close; k++) {
            Token t = tokens.get(k);
            if (t.comment()) return -1;
            length += t.text().length();
            if (t.is(',') || t.is(':')) length++;
            if (t.is('{') && !tokens.get(k + 1).is('}')) length += 2;
        }
        return length;
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
