package dev.jpm.format.intellij;

import dev.jpm.format.Source;
import dev.jpm.format.api.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IntelliJ's default Javadoc formatting, which the layout engine leaves to it: "Align parameter descriptions" and
 * "Align thrown exception descriptions" (the descriptions of {@code @param} tags, and of {@code @throws} and
 * {@code @exception} tags, start in one column, with their continuation lines under them), "Blank lines: after
 * description" (a blank line between the description and the first block tag), "Do not wrap one line comments"
 * being off ({@code /** Text. *}{@code /} becomes three lines), and "Indent continuation lines" being off (the
 * continuation lines of other block tags start under the tag), and "Generate {@code <p>} on empty lines" (an empty
 * line of the description that more text follows gets a {@code <p>}, outside {@code <pre>}). The parameter types
 * of {@code {@link}} and {@code @see} references are separated by a comma and a space. A description written on the line after its
 * {@code @param} or {@code @throws} name is joined onto the tag's line.
 *
 * <p>Text is not re-wrapped, and comments that do not have a {@code *} at the start of every line, or that contain
 * {@code <pre>} in their tags, are left as they are.
 */
public final class JavadocLayout implements IntelliJRule {
    public static final String ID = "JavadocLayout";

    private static final Pattern TAG = Pattern.compile("^@(param|throws|exception)\\s+(\\S+)(\\s+(.*))?$");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String format(Context ctx) {
        String src = ctx.source();
        String nl = Source.newline(src);
        StringBuilder out = new StringBuilder(src.length() + 64);
        int i = 0, copied = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(src, i);
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                if (end < 0) break;
                end += 2;
                if (src.startsWith("/**", i) && !src.startsWith("/**/", i)) {
                    String formatted = javadoc(src.substring(i, end), column(src, i), nl);
                    if (formatted != null) {
                        out.append(src, copied, i).append(formatted);
                        copied = end;
                    }
                }
                i = end;
            } else {
                i++;
            }
        }
        if (copied == 0) return src;
        return out.append(src, copied, n).toString();
    }

    private static int skipLiteral(String src, int i) {
        char q = src.charAt(i);
        if (q == '"' && src.startsWith("\"\"\"", i)) {
            int end = src.indexOf("\"\"\"", i + 3);
            while (end > 0 && src.charAt(end - 1) == '\\') end = src.indexOf("\"\"\"", end + 1);
            return end < 0 ? src.length() : end + 3;
        }
        int j = i + 1;
        while (j < src.length()) {
            char c = src.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == q || c == '\n') return j + 1;
            j++;
        }
        return j;
    }

    private static int column(String src, int i) {
        int start = src.lastIndexOf('\n', i - 1) + 1;
        for (int k = start; k < i; k++) if (!Character.isWhitespace(src.charAt(k))) return -1;
        return i - start;
    }

    static String javadoc(String text, int column, String nl) {
        if (column < 0) return null;
        String indent = " ".repeat(column);
        if (!text.contains("\n")) {
            String inner = text.substring(3, text.length() - 2).strip();
            return inner.isEmpty() ? null : expand(indent, inner, nl);
        }
        String[] raw = text.split("\\r?\\n", -1);
        if (!raw[0].strip().equals("/**") || !raw[raw.length - 1].strip().equals("*/")) return null;
        List<String> body = new ArrayList<>();
        for (int k = 1; k < raw.length - 1; k++) {
            String l = raw[k].stripLeading();
            if (!l.startsWith("*")) return null;
            String rest = l.substring(1);
            body.add(rest.startsWith(" ") ? rest.substring(1) : rest);
        }
        int firstTag = -1;
        for (int k = 0; k < body.size(); k++) {
            if (body.get(k).startsWith("@")) {
                firstTag = k;
                break;
            }
        }
        boolean tagged = firstTag >= 0;
        if (!tagged) firstTag = body.size();
        for (int k = firstTag; k < body.size(); k++) if (body.get(k).contains("<pre>")) return null;
        List<String> lines = new ArrayList<>(body);
        paragraphs(lines, firstTag);
        boolean described = false;
        for (int k = 0; k < firstTag; k++) described |= !lines.get(k).isBlank();
        if (tagged && described && !lines.get(firstTag - 1).isBlank()) {
            lines.add(firstTag, "");
            firstTag++;
        }
        references(lines);
        join(lines, firstTag);
        tagBlankLines(lines, firstTag);
        align(lines, firstTag, "param");
        align(lines, firstTag, "throws");
        unindent(lines, firstTag);
        if (lines.equals(body)) return null;
        StringBuilder sb = new StringBuilder("/**").append(nl);
        for (String l : lines)
            sb.append(indent).append(l.isEmpty() ? " *" : " * " + l).append(nl);
        return sb.append(indent).append(" */").toString();
    }

    private static String expand(String indent, String inner, String nl) {
        return "/**" + nl + indent + " * " + inner + nl + indent + " */";
    }

    private static void tagBlankLines(List<String> lines, int firstTag) {
        for (int k = lines.size() - 2; k > firstTag; k--) {
            if (lines.get(k).isBlank() && lines.get(k + 1).startsWith("@")) lines.remove(k);
        }
        for (int k = firstTag; k < lines.size(); k++) {
            if (lines.get(k).startsWith("@return "))
                lines.set(k, "@return " + lines.get(k).substring(8).stripLeading());
        }
    }

    private static void align(List<String> lines, int firstTag, String group) {
        List<Integer> tags = new ArrayList<>();
        int width = 0;
        for (int k = firstTag; k < lines.size(); k++) {
            Matcher m = TAG.matcher(lines.get(k));
            if (!m.matches() || !inGroup(m.group(1), group)) continue;
            tags.add(k);
            width = Math.max(width, m.group(2).length());
        }
        for (int k : tags) {
            Matcher m = TAG.matcher(lines.get(k));
            if (!m.matches()) continue;
            String desc = m.group(4);
            String head = "@" + m.group(1) + " " + m.group(2);
            if (desc == null || desc.isEmpty()) continue;
            int column = ("@" + m.group(1) + " ").length() + width + 1;
            lines.set(k, head + " ".repeat(column - head.length()) + desc);
            for (int c = k + 1; c < lines.size(); c++) {
                String l = lines.get(c);
                if (l.isBlank() || l.startsWith("@")) break;
                lines.set(c, " ".repeat(column) + l.strip());
            }
        }
    }

    private static void paragraphs(List<String> lines, int firstTag) {
        boolean pre = false, text = false;
        for (int k = 0; k < firstTag; k++) {
            String l = lines.get(k);
            if (l.contains("<pre>")) pre = true;
            if (l.contains("</pre>")) pre = false;
            if (!l.isBlank()) {
                text = true;
                continue;
            }
            if (pre || !text || k + 1 >= firstTag) continue;
            String n = lines.get(k + 1).strip();
            if (n.isEmpty() || n.startsWith("<")) continue;
            lines.set(k, "<p>");
        }
    }

    private static final Pattern LINK = Pattern.compile("(\\{@link(?:plain)?\\s+[^}\\s(]*\\()([^)}]*)(\\))");
    private static final Pattern SEE = Pattern.compile("^(@see\\s+[^\\s(]*\\()([^)]*)(\\))");

    private static void references(List<String> lines) {
        boolean pre = false;
        for (int k = 0; k < lines.size(); k++) {
            String l = lines.get(k);
            if (l.contains("<pre>")) pre = true;
            if (pre) {
                if (l.contains("</pre>")) pre = false;
                continue;
            }
            lines.set(k, spaced(SEE, spaced(LINK, l)));
        }
    }

    private static String spaced(Pattern p, String l) {
        Matcher m = p.matcher(l);
        StringBuilder sb = new StringBuilder();
        while (m.find())
            m.appendReplacement(
                    sb, Matcher.quoteReplacement(m.group(1) + m.group(2).replaceAll(",\\s*", ", ") + m.group(3)));
        m.appendTail(sb);
        return sb.toString();
    }

    private static void join(List<String> lines, int firstTag) {
        for (int k = firstTag; k + 1 < lines.size(); k++) {
            Matcher m = TAG.matcher(lines.get(k));
            if (!m.matches() || m.group(4) != null && !m.group(4).isEmpty()) continue;
            String next = lines.get(k + 1);
            if (next.isBlank() || next.strip().startsWith("@")) continue;
            lines.set(k, "@" + m.group(1) + " " + m.group(2) + " " + next.strip());
            lines.remove(k + 1);
        }
    }

    private static void unindent(List<String> lines, int firstTag) {
        boolean inOther = false;
        for (int k = firstTag; k < lines.size(); k++) {
            String l = lines.get(k);
            if (l.startsWith("@")) {
                Matcher m = TAG.matcher(l);
                inOther = !m.matches();
                continue;
            }
            if (l.isBlank()) {
                inOther = false;
                continue;
            }
            if (inOther) lines.set(k, l.strip());
        }
    }

    private static boolean inGroup(String tag, String group) {
        return group.equals("param") ? tag.equals("param") : tag.equals("throws") || tag.equals("exception");
    }
}
