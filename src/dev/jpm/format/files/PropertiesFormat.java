package dev.jpm.format.files;

/**
 * {@code .properties} files as IntelliJ IDEA's default code style formats them: no whitespace before a key or a
 * comment, and none around the {@code =} or {@code :} between a key and its value ({@code key = value} becomes
 * {@code key=value}); a key and value separated by whitespace alone get one space. Values are left as they are,
 * trailing whitespace included, since it is part of the value, and so are the continuation lines of a value that
 * goes on over several lines.
 *
 * <p>Unlike IntelliJ, which removes every blank line, blank lines are kept, up to {@code maxBlankLines} in a row, so
 * that groups of keys stay apart; blank lines at the start and end of the file are removed. Text has {@code \n} line
 * ends.
 *
 * <p>Two options, both off by default: {@code width} above 0 wraps an entry longer than that at spaces in its value,
 * with continuation lines {@code indent} in (a backslash at the end of a line goes on to the next, without its leading
 * whitespace, so the value stays the same); {@code encoding} {@code "ISO-8859-1"} writes every character outside
 * ASCII as a Unicode escape (a backslash, {@code u} and four hex digits), as {@code java.util.Properties} reads such
 * files, and {@code "UTF-8"} writes those escapes as the characters, for files read as UTF-8.
 */
public final class PropertiesFormat {
    private PropertiesFormat() {
    }

    public static String format(String text, int maxBlankLines) {
        return format(text, maxBlankLines, 0, 4, null);
    }

    public static String format(String text, int maxBlankLines, int width, int indent, String encoding) {
        String[] lines = text.split("\n", -1);
        boolean finalNewline = text.endsWith("\n");
        int count = finalNewline ? lines.length - 1 : lines.length;
        StringBuilder out = new StringBuilder(text.length());
        int blanks = 0;
        boolean started = false;
        boolean continued = false;
        for (int k = 0; k < count; k++) {
            String line = lines[k];
            if (continued) {
                out.append(encode(line, encoding)).append('\n');
                continued = continues(line);
                continue;
            }
            String stripped = stripLeading(line);
            if (stripped.isEmpty()) {
                if (started) blanks++;
                continue;
            }
            if (started) out.append("\n".repeat(Math.min(blanks, maxBlankLines)));
            blanks = 0;
            started = true;
            char c = stripped.charAt(0);
            if (c == '#' || c == '!') {
                String comment = stripped.stripTrailing();
                out.append("ISO-8859-1".equals(encoding) ? encode(comment, encoding) : comment).append('\n');
                continue;
            }
            continued = continues(line);
            String entry = encode(entry(stripped), encoding);
            out.append(continued || width <= 0 ? entry : wrap(entry, width, indent)).append('\n');
        }
        if (!finalNewline && !out.isEmpty()) out.setLength(out.length() - 1);
        return out.toString();
    }

    private static String entry(String line) {
        int n = line.length();
        int i = 0;
        while (i < n) {
            char c = line.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '=' || c == ':' || whitespace(c)) break;
            i++;
        }
        i = Math.min(i, n);
        String key = line.substring(0, i);
        int j = i;
        while (j < n && whitespace(line.charAt(j))) j++;
        String separator = " ";
        if (j < n && (line.charAt(j) == '=' || line.charAt(j) == ':')) {
            separator = String.valueOf(line.charAt(j));
            j++;
            while (j < n && whitespace(line.charAt(j))) j++;
        }
        String value = line.substring(j);
        if (value.isEmpty() && separator.equals(" ")) return key;
        return key + separator + value;
    }

    private static String wrap(String entry, int width, int indent) {
        if (entry.length() <= width) return entry;
        StringBuilder out = new StringBuilder();
        int start = 0;
        int lastBreak = -1;
        for (int k = 0; k < entry.length(); k++) {
            char c = entry.charAt(k);
            if (c == '\\') {
                k++;
                continue;
            }
            if (c == ' ' && k > separator(entry)) lastBreak = k + 1;
            int length = k - start + 1 + (out.isEmpty() ? 0 : indent);
            if (length + 2 > width && lastBreak > start) {
                out.append(out.isEmpty() ? "" : " ".repeat(indent)).append(entry, start, lastBreak).append("\\\n");
                start = lastBreak;
            }
        }
        if (out.isEmpty()) return entry;
        out.append(" ".repeat(indent)).append(entry, start, entry.length());
        return out.toString();
    }

    private static int separator(String entry) {
        for (int k = 0; k < entry.length(); k++) {
            char c = entry.charAt(k);
            if (c == '\\') k++;
            else if (c == '=' || c == ':' || c == ' ') return k;
        }
        return entry.length();
    }

    private static String encode(String s, String encoding) {
        if (encoding == null) return s;
        StringBuilder out = new StringBuilder(s.length());
        if (encoding.equals("ISO-8859-1")) {
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c > 0x7f) out.append(String.format("\\u%04X", (int) c));
                else out.append(c);
            }
            return out.toString();
        }
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\' && k + 1 < s.length() && s.charAt(k + 1) != 'u') {
                out.append(c).append(s.charAt(k + 1));
                k++;
                continue;
            }
            int u = escape(s, k);
            if (u >= 0xa0 && !Character.isSurrogate((char) u)) {
                out.append((char) u);
                k += 5;
            } else if (Character.isHighSurrogate((char) u) && Character.isLowSurrogate((char) escape(s, k + 6))) {
                out.append((char) u).append((char) escape(s, k + 6));
                k += 11;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int escape(String s, int k) {
        if (k + 6 > s.length() || s.charAt(k) != '\\' || s.charAt(k + 1) != 'u') return -1;
        try {
            return Integer.parseInt(s.substring(k + 2, k + 6), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean continues(String line) {
        int backslashes = 0;
        for (int k = line.length() - 1; k >= 0 && line.charAt(k) == '\\'; k--) backslashes++;
        return backslashes % 2 == 1;
    }

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && whitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    private static boolean whitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }
}
