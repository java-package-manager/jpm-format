package dev.jpm.format.intellij;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import dev.jpm.format.Source;
import dev.jpm.format.api.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * {@code @param} or {@code @throws} name is joined onto the tag's line. Block tags are put in IntelliJ's order:
 * {@code @param}, {@code @return}, {@code @throws} ({@code @exception} becomes {@code @throws}), the other tags,
 * {@code @see}, {@code @since}, {@code @deprecated}; tags of one kind keep their order, and a tag keeps its
 * continuation lines. Lines lose their indentation outside {@code <pre>} and {@code {@snippet}}; from a line that
 * starts a {@code <table>}, {@code <ol>}, {@code <ul>}, {@code <div>} or {@code <dl>} on, they keep it less the
 * smallest one (IntelliJ's {@code JDParser}).
 *
 * <p>Text is not re-wrapped, and comments that do not have a {@code *} at the start of every line are left as they
 * are. A {@code <pre>} anywhere in a comment does not stop the rest of it from being formatted, as in IntelliJ.
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
        Set<Integer> owners = owners(ctx);
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
                if (src.startsWith("/**", i) && !src.startsWith("/**/", i)
                        && (owners == null || owners.contains(nextCode(src, end)))) {
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

    /**
     * Where the declarations start whose Javadoc IntelliJ formats: the package, the module, top-level types and the
     * fields, methods and classes of the classes among them, not those of local or anonymous classes, nor
     * initializers or local variables. Null without a tree, and then every comment is formatted.
     */
    private static Set<Integer> owners(Context ctx) {
        CompilationUnitTree cu;
        try {
            cu = ctx.tree();
        } catch (RuntimeException e) {
            return null;
        }
        if (cu == null) return null;
        Set<Integer> out = new HashSet<>();
        if (cu.getPackage() != null) out.add(ctx.start(cu.getPackage()));
        if (cu.getModule() != null) out.add(ctx.start(cu.getModule()));
        for (Tree t : cu.getTypeDecls()) if (t instanceof ClassTree c) members(ctx, c, out);
        return out;
    }

    private static void members(Context ctx, ClassTree c, Set<Integer> out) {
        out.add(ctx.start(c));
        for (Tree m : c.getMembers()) {
            if (m instanceof ClassTree inner) members(ctx, inner, out);
            else if (m instanceof MethodTree || m instanceof VariableTree) out.add(ctx.start(m));
        }
    }

    private static int nextCode(String src, int i) {
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (src.startsWith("//", i)) {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                if (end < 0) return n;
                i = end + 2;
            } else {
                return i;
            }
        }
        return n;
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
        boolean unspaced = false;
        int inPre = 0;
        for (int k = 1; k < raw.length - 1; k++) {
            String l = raw[k].stripLeading();
            if (!l.startsWith("*")) return null;
            String rest = l.substring(1);
            if (!rest.isEmpty() && !rest.startsWith(" ")) unspaced = true;
            String line = rest.startsWith(" ") ? rest.substring(1) : rest;
            if (inPre == 0 && line.startsWith(" ") && line.stripLeading().startsWith("@")) line = line.stripLeading();
            body.add(line);
            inPre = Math.max(0, inPre + preDepth(line));
        }
        int firstTag = -1, pre = 0;
        for (int k = 0; k < body.size(); k++) {
            String l = body.get(k);
            if (pre == 0 && l.startsWith("@")) {
                firstTag = k;
                break;
            }
            pre = Math.max(0, pre + preDepth(l));
        }
        boolean tagged = firstTag >= 0;
        if (!tagged) firstTag = body.size();
        List<String> lines = new ArrayList<>(body);
        indents(lines, firstTag);
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
        reorder(lines, firstTag);
        align(lines, firstTag, "param");
        align(lines, firstTag, "throws");
        if (lines.equals(body) && !unspaced) return null;
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

    private static void reorder(List<String> lines, int firstTag) {
        if (firstTag >= lines.size()) return;
        List<String> tail = lines.subList(firstTag, lines.size());
        for (String l : tail) if (PRE_START.matcher(l).find()) return;
        int end = tail.size();
        while (end > 0 && tail.get(end - 1).isBlank()) end--;
        List<List<String>> blocks = new ArrayList<>();
        for (int k = 0; k < end; k++) {
            String l = tail.get(k);
            if (l.startsWith("@") || blocks.isEmpty()) blocks.add(new ArrayList<>());
            blocks.getLast().add(l.startsWith("@exception ") ? "@throws " + l.substring(11) : l);
        }
        List<List<String>> sorted = new ArrayList<>(blocks);
        sorted.sort(java.util.Comparator.comparingInt(b -> rank(b.getFirst())));
        List<String> out = new ArrayList<>();
        for (List<String> b : sorted) out.addAll(b);
        out.addAll(tail.subList(end, tail.size()));
        tail.clear();
        tail.addAll(out);
    }

    private static int rank(String tagLine) {
        int space = tagLine.indexOf(' ');
        String tag = space < 0 ? tagLine : tagLine.substring(0, space);
        return switch (tag) {
            case "@param" -> 0;
            case "@return" -> 1;
            case "@throws", "@exception" -> 2;
            case "@see" -> 4;
            case "@since" -> 5;
            case "@deprecated" -> 6;
            default -> 3;
        };
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
            if (PRE_START.matcher(l).find()) pre = true;
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
            if (PRE_START.matcher(l).find()) pre = true;
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

    private static final Pattern PRE_START = Pattern.compile("<pre\\s*(\\w+\\s*=.*)?>");
    private static final Pattern KEEP_INDENTS = Pattern.compile("^<(table|ol|ul|div|dl)[\\s>/]");

    private static void indents(List<String> lines, int firstTag) {
        indents(lines, 0, firstTag, false);
        for (int k = firstTag; k < lines.size(); k++) {
            if (!lines.get(k).startsWith("@")) continue;
            int end = k + 1;
            while (end < lines.size() && !lines.get(end).startsWith("@")) end++;
            if (!TAG.matcher(lines.get(k)).matches()) indents(lines, k, end, true);
            k = end - 1;
        }
    }

    private static void indents(List<String> lines, int from, int to, boolean tag) {
        int keepFrom = -1, min = Integer.MAX_VALUE, pre = 0, snippet = 0;
        for (int k = from; k < to; k++) {
            String l = lines.get(k);
            String text = tag && k == from ? tagText(l) : l;
            if (keepFrom < 0 && KEEP_INDENTS.matcher(text.strip() + " ").find()) keepFrom = k;
            if (keepFrom >= 0) min = Math.min(min, tag && k == from ? 0 : indent(l));
            if (!(tag && k == from) && pre == 0 && keepFrom < 0 && snippet == 0) lines.set(k, l.strip());
            if (snippet > 0) {
                snippet += braces(l);
            } else if (l.contains("{@snippet")) {
                snippet = braces(l.substring(l.indexOf("{@snippet")));
            }
            pre = Math.max(0, pre + preDepth(l));
        }
        if (keepFrom < 0 || min <= 0 || min == Integer.MAX_VALUE) return;
        for (int k = Math.max(keepFrom, tag ? from + 1 : from); k < to; k++) {
            String l = lines.get(k);
            if (!l.isBlank() && l.length() > min) lines.set(k, l.substring(min));
        }
    }

    private static int preDepth(String l) {
        Matcher m = PRE_START.matcher(l);
        int open = -1;
        while (m.find()) open = m.start();
        int close = l.lastIndexOf("</pre>");
        if (open >= 0 && close < open) return 1;
        return close >= 0 && open < 0 ? -1 : 0;
    }

    private static String tagText(String tagLine) {
        Matcher m = TAG.matcher(tagLine);
        if (m.matches()) return m.group(4) == null ? "" : m.group(4);
        int space = tagLine.indexOf(' ');
        return space < 0 ? "" : tagLine.substring(space + 1);
    }

    private static int indent(String l) {
        int i = 0;
        while (i < l.length() && (l.charAt(i) == ' ' || l.charAt(i) == '\t')) i++;
        return i == l.length() ? Integer.MAX_VALUE : i;
    }

    private static int braces(String l) {
        int n = 0;
        for (int i = 0; i < l.length(); i++) {
            if (l.charAt(i) == '{') n++;
            if (l.charAt(i) == '}') n--;
        }
        return n;
    }

    private static boolean inGroup(String tag, String group) {
        return group.equals("param") ? tag.equals("param") : tag.equals("throws") || tag.equals("exception");
    }
}
