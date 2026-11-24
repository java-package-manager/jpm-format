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
 */
public final class PropertiesFormat {
    private PropertiesFormat() {
    }

    public static String format(String text, int maxBlankLines) {
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
                out.append(line).append('\n');
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
                out.append(stripped.stripTrailing()).append('\n');
                continue;
            }
            out.append(entry(stripped)).append('\n');
            continued = continues(line);
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
