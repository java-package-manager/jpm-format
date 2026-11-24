package dev.jpm.format.files;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XML files as IntelliJ IDEA's default code style formats them, without wrapping long lines: one indent per level of
 * elements; an element whose content is only elements, comments and processing instructions gets each of them on a
 * line of its own and its end tag on a line of its own; a start tag's attributes are separated by one space, with no
 * spaces around {@code =} or before {@code >} and {@code />}, and an attribute written on a line of its own lines up
 * with the first one.
 *
 * <p>Text is never changed: an element with text in it (mixed content, CDATA included) keeps its content as written,
 * and so does one with {@code xml:space="preserve"}. Blank lines between elements are kept, up to
 * {@code maxBlankLines} in a row. Text that does not parse as XML is left as it is. Text has {@code \n} line ends.
 */
public final class XmlFormat {
    private static final Pattern ATTRIBUTE =
            Pattern.compile("(\\s*)([^\\s=/>]+)\\s*=\\s*(\"[^\"]*\"|'[^']*')|(\\s*)([^\\s=/>]+)");

    private sealed interface Node permits Text, Markup, Element {
    }

    private record Text(String text) implements Node {
    }

    private record Markup(String text, int column) implements Node {
    }

    private record Element(String name, String startTag, int startColumn, boolean empty, List<Node> children,
                           int contentStart, int contentEnd, String endTag) implements Node {
    }

    private static final class Malformed extends Exception {
        Malformed() {
            super(null, null, false, false);
        }
    }

    private final String text;
    private final int indent;
    private final int maxBlank;
    private int pos;

    private XmlFormat(String text, int indent, int maxBlank) {
        this.text = text;
        this.indent = indent;
        this.maxBlank = maxBlank;
    }

    public static String format(String text, int indent, int maxBlankLines) {
        XmlFormat f = new XmlFormat(text, indent, maxBlankLines);
        try {
            List<Node> nodes = f.parseContent(null);
            if (f.pos != text.length()) return text;
            for (Node node : nodes) if (node instanceof Text t && !t.text().isBlank()) return text;
            StringBuilder out = new StringBuilder(text.length());
            f.layout(nodes, 0, out, true);
            strip(out);
            if (text.endsWith("\n") && !out.isEmpty()) out.append('\n');
            return out.toString();
        } catch (Malformed e) {
            return text;
        }
    }

    private List<Node> parseContent(String parent) throws Malformed {
        List<Node> nodes = new ArrayList<>();
        int n = text.length();
        while (pos < n) {
            if (text.startsWith("</", pos)) {
                if (parent == null) throw new Malformed();
                return nodes;
            }
            int column = column(pos);
            if (text.startsWith("<!--", pos)) {
                nodes.add(new Markup(until("-->"), column));
            } else if (text.startsWith("<![CDATA[", pos)) {
                nodes.add(new Text(until("]]>")));
            } else if (text.startsWith("<?", pos)) {
                nodes.add(new Markup(until("?>"), column));
            } else if (text.startsWith("<!", pos)) {
                nodes.add(new Markup(declaration(), column));
            } else if (text.charAt(pos) == '<') {
                nodes.add(element());
            } else {
                int next = text.indexOf('<', pos);
                if (next < 0) next = n;
                nodes.add(new Text(text.substring(pos, next)));
                pos = next;
            }
        }
        if (parent != null) throw new Malformed();
        return nodes;
    }

    private String until(String close) throws Malformed {
        int end = text.indexOf(close, pos);
        if (end < 0) throw new Malformed();
        String s = text.substring(pos, end + close.length());
        pos = end + close.length();
        return s;
    }

    private String declaration() throws Malformed {
        int start = pos;
        int depth = 0;
        for (int i = pos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == '>' && depth == 0) {
                pos = i + 1;
                return text.substring(start, pos);
            }
        }
        throw new Malformed();
    }

    private Element element() throws Malformed {
        int start = pos;
        int column = column(pos);
        int i = pos + 1;
        char quote = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                break;
            }
            i++;
        }
        if (i >= text.length()) throw new Malformed();
        String tag = text.substring(start, i + 1);
        pos = i + 1;
        int nameEnd = 1;
        while (nameEnd < tag.length() && !Character.isWhitespace(tag.charAt(nameEnd)) && tag.charAt(nameEnd) != '/'
                && tag.charAt(nameEnd) != '>') {
            nameEnd++;
        }
        String name = tag.substring(1, nameEnd);
        if (name.isEmpty()) throw new Malformed();
        boolean empty = tag.endsWith("/>");
        if (empty) return new Element(name, tag, column, true, List.of(), pos, pos, "");
        int contentStart = pos;
        List<Node> children = parseContent(name);
        int contentEnd = pos;
        int close = text.indexOf('>', pos);
        if (close < 0) throw new Malformed();
        String end = text.substring(pos, close + 1);
        if (!end.substring(2, end.length() - 1).strip().equals(name)) throw new Malformed();
        pos = close + 1;
        return new Element(name, tag, column, false, children, contentStart, contentEnd, end);
    }

    private void layout(List<Node> nodes, int level, StringBuilder out, boolean top) {
        boolean first = top;
        int pending = 0;
        boolean gap = false;
        for (Node node : nodes) {
            if (node instanceof Text t) {
                pending += count(t.text(), '\n');
                gap = !t.text().isEmpty();
                continue;
            }
            if (first) {
                first = false;
            } else {
                strip(out);
                if (trailing(node, pending)) out.append(gap ? " " : "");
                else
                    out.append("\n".repeat(Math.max(1, Math.min(pending, maxBlank + 1)))).append(" ".repeat(level * indent));
            }
            pending = 0;
            gap = false;
            int column = out.length() - out.lastIndexOf("\n") - 1;
            switch (node) {
                case Markup m -> out.append(shift(m.text(), column - m.column()));
                case Element e -> element(e, level, column, out);
                case Text t -> out.append(t.text());
            }
        }
    }

    private void element(Element e, int level, int column, StringBuilder out) {
        out.append(startTag(e, column));
        if (e.empty()) return;
        boolean layout = !preserves(e.startTag()) && !e.children().isEmpty()
                && e.children().stream().allMatch(c -> c instanceof Text t ? t.text().isBlank() && !t.text()
                .startsWith("<![CDATA[") : true)
                && e.children().stream().anyMatch(c -> !(c instanceof Text));
        String content = text.substring(e.contentStart(), e.contentEnd());
        if (!layout && !preserves(e.startTag()) && content.isBlank() && content.indexOf('\n') >= 0) {
            out.append("\n".repeat(Math.min(count(content, '\n'), maxBlank + 1))).append(" ".repeat(level * indent))
                    .append(e.endTag().replaceAll("\\s+>$", ">"));
            return;
        }
        if (!layout) {
            out.append(content).append(e.endTag().replaceAll("\\s+>$", ">"));
            return;
        }
        int pending = 0;
        boolean gap = false;
        for (Node c : e.children()) {
            if (c instanceof Text t) {
                pending += count(t.text(), '\n');
                gap = !t.text().isEmpty();
                continue;
            }
            strip(out);
            if (trailing(c, pending)) {
                out.append(gap ? " " : "");
            } else {
                out.append("\n".repeat(Math.max(1, Math.min(pending, maxBlank + 1))));
                out.append(" ".repeat((level + 1) * indent));
            }
            pending = 0;
            gap = false;
            int col = out.length() - out.lastIndexOf("\n") - 1;
            switch (c) {
                case Markup m -> out.append(shift(m.text(), col - m.column()));
                case Element child -> element(child, level + 1, col, out);
                case Text t -> out.append(t.text());
            }
        }
        strip(out);
        out.append("\n".repeat(Math.max(1, Math.min(pending, maxBlank + 1))));
        out.append(" ".repeat(level * indent)).append(e.endTag().replaceAll("\\s+>$", ">"));
    }

    private String startTag(Element e, int column) {
        String tag = e.startTag();
        String body = tag.substring(1 + e.name().length(), tag.length() - (e.empty() ? 2 : 1));
        StringBuilder b = new StringBuilder("<").append(e.name());
        Matcher m = ATTRIBUTE.matcher(body);
        int align = -1;
        int at = 0;
        while (at < body.length() && m.find(at) && m.start() == at) {
            String gap = m.group(1) != null ? m.group(1) : m.group(4);
            String attribute = m.group(2) != null ? m.group(2) + "=" + m.group(3) : m.group(5);
            boolean newLine = gap.indexOf('\n') >= 0;
            if (align < 0) align = newLine ? column + 2 * indent : column + 1 + e.name().length() + 1;
            if (newLine) b.append('\n').append(" ".repeat(align));
            else b.append(' ');
            b.append(attribute);
            at = m.end();
        }
        if (!body.substring(at).isBlank()) return tag;
        return b.append(e.empty() ? "/>" : ">").toString();
    }

    private static boolean preserves(String startTag) {
        return startTag.matches("(?s).*\\sxml:space\\s*=\\s*[\"']preserve[\"'].*");
    }

    private int column(int offset) {
        int col = 0;
        for (int k = text.lastIndexOf('\n', offset - 1) + 1; k < offset; k++) {
            col = text.charAt(k) == '\t' ? col / 4 * 4 + 4 : col + 1;
        }
        return col;
    }

    private static boolean trailing(Node node, int newlines) {
        return newlines == 0 && node instanceof Markup m && m.text().startsWith("<!--");
    }

    private static String shift(String markup, int delta) {
        if (markup.indexOf('\n') < 0) return markup;
        String[] parts = markup.split("\n", -1);
        StringBuilder b = new StringBuilder(parts[0].stripTrailing());
        for (int k = 1; k < parts.length; k++) {
            String p = parts[k];
            b.append('\n');
            if (p.isBlank()) continue;
            int lead = 0;
            int i = 0;
            while (i < p.length() && (p.charAt(i) == ' ' || p.charAt(i) == '\t')) {
                lead = p.charAt(i) == '\t' ? lead / 4 * 4 + 4 : lead + 1;
                i++;
            }
            b.append(" ".repeat(Math.max(0, lead + delta))).append(p.substring(i).stripTrailing());
        }
        return b.toString();
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
