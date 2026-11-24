package dev.jpm.format.files;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAML files as IntelliJ IDEA's default code style indents them: each level of a mapping {@code indent} in from its
 * key, a sequence under a key one level in too ({@code - item} under {@code list:}), and one space after {@code key:}
 * and after the {@code -} of an item. A block scalar ({@code |} or {@code >}) moves with its key and keeps the
 * indentation of its lines relative to each other; values, quoted or not, flow collections and comments stay as
 * written, comments at the indent of the line after them. Blank lines are kept, up to {@code maxBlankLines}.
 *
 * <p>Indentation is part of YAML's syntax, so a file is left as it is when its structure is not plain enough to be
 * sure of it: tabs in an indent, a block scalar with an explicit indentation indicator, a flow collection or a quoted
 * value that goes on over lines, or a line indented back to a level that no line above it has. Text has {@code \n}
 * line ends.
 */
public final class YamlFormat {
    private static final Pattern KEY = Pattern.compile(
            "^((?:\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^']|'')*'|[^\\s#'\"\\[\\]{},:][^#:]*?|\\?[^:]*?))\\s*:(?:\\s+|$)(.*)$");
    private static final Pattern BLOCK = Pattern.compile("^[|>][+-]?(\\s+#.*)?$");

    private record Level(int original, int indent, boolean container, int child) {
    }

    private YamlFormat() {
    }

    public static String format(String text, int indent, int maxBlankLines) {
        String[] lines = text.split("\n", -1);
        boolean finalNewline = text.endsWith("\n");
        int count = finalNewline ? lines.length - 1 : lines.length;
        String[] out = new String[count];
        int[] indents = new int[count];
        Deque<Level> levels = new ArrayDeque<>();
        int block = -1;
        int blockTarget = 0;
        Integer blockShift = null;
        boolean opensChild = false;
        for (int k = 0; k < count; k++) {
            String line = lines[k];
            int lead = lead(line);
            if (lead < 0) return text;
            String body = line.substring(lead);
            if (block >= 0) {
                if (body.isEmpty() || lead > block) {
                    if (blockShift == null && !body.isEmpty()) blockShift = blockTarget - lead;
                    int shift = blockShift == null ? 0 : blockShift;
                    out[k] = body.isEmpty() ? "" : " ".repeat(Math.max(0, lead + shift)) + body;
                    indents[k] = -2;
                    continue;
                }
                block = -1;
            }
            if (body.isEmpty() || body.startsWith("#")) {
                out[k] = body.isEmpty() ? "" : body.stripTrailing();
                indents[k] = body.isEmpty() ? -1 : -3;
                continue;
            }
            if (body.startsWith("---") || body.startsWith("...") || body.startsWith("%")) {
                levels.clear();
                opensChild = false;
                out[k] = body;
                indents[k] = 0;
                continue;
            }
            Level parent = null;
            while (!levels.isEmpty() && levels.peek().original() > lead) levels.pop();
            boolean dash = body.equals("-") || body.startsWith("- ");
            if (!levels.isEmpty() && levels.peek().original() == lead) {
                Level top = levels.peek();
                if (dash && opensChild && !top.container()) {
                    parent = top;
                } else {
                    levels.pop();
                    parent = levels.peek();
                }
            } else if (!levels.isEmpty()) {
                if (!opensChild) return text;
                parent = levels.peek();
            } else if (lead > 0 && k > 0 && opensChild) {
                return text;
            }
            int at = parent == null ? 0 : parent.child();
            if (parent == null && lead > 0) return text;
            String rest = body;
            StringBuilder written = new StringBuilder();
            int column = at;
            int original = lead;
            while (rest.startsWith("- ") || rest.equals("-")) {
                levels.push(new Level(original, column, true, column + 2));
                String after = rest.length() > 1 ? rest.substring(1) : "";
                int spaces = lead(after);
                if (spaces < 0) return text;
                original += 1 + spaces;
                written.append(after.isEmpty() ? "-" : "- ");
                rest = after.substring(spaces);
                column += 2;
                if (rest.isEmpty()) break;
            }
            levels.push(new Level(original, column, false, column + indent));
            String value = rest;
            Matcher m = KEY.matcher(rest);
            boolean child = rest.isEmpty();
            if (m.matches()) {
                value = m.group(2);
                written.append(m.group(1)).append(':');
                if (!value.isEmpty()) written.append(' ').append(value);
                child = value.isEmpty() || value.startsWith("#");
            } else {
                written.append(rest);
            }
            if (open(value)) return text;
            String scalar = value.replaceFirst("^&\\S+\\s+", "").replaceFirst("^!\\S+\\s+", "");
            if (scalar.matches("^[|>][+-]?[1-9].*")) return text;
            if (BLOCK.matcher(scalar).matches()) {
                block = original;
                blockTarget = column + indent;
                blockShift = null;
                child = false;
            }
            opensChild = child;
            out[k] = " ".repeat(at) + written;
            indents[k] = at;
        }
        for (int k = count - 1, next = 0; k >= 0; k--) {
            if (indents[k] >= 0) next = indents[k];
            else if (indents[k] == -3) out[k] = " ".repeat(next) + out[k];
        }
        StringBuilder b = new StringBuilder(text.length());
        int blanks = 0;
        boolean started = false;
        for (int k = 0; k < count; k++) {
            if (out[k].isEmpty() && indents[k] != -2) {
                if (started) blanks++;
                continue;
            }
            if (started) b.append("\n".repeat(Math.min(blanks, maxBlankLines)));
            blanks = 0;
            started = true;
            b.append(out[k]).append('\n');
        }
        if (!finalNewline && !b.isEmpty()) b.setLength(b.length() - 1);
        return b.toString();
    }

    private static int lead(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        if (i < line.length() && line.charAt(i) == '\t') return -1;
        return i;
    }

    private static boolean open(String value) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') i++;
                else if (c == quote) quote = 0;
            } else if (c == '#' && (i == 0 || value.charAt(i - 1) == ' ')) {
                break;
            } else if ((c == '"' || c == '\'') && (i == 0 || " [{,:".indexOf(value.charAt(i - 1)) >= 0)) {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            }
        }
        return depth != 0 || quote != 0;
    }
}
