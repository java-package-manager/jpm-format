package dev.jpm.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON and JSONC reader and JSON writer. JSONC adds comments and trailing commas. Objects become
 * {@link LinkedHashMap}s, arrays {@link ArrayList}s, numbers {@link Long} or {@link Double}.
 */
public final class Json {
    private final String s;
    private final String where;
    private int i;

    private Json(String s, String where) {
        this.s = s;
        this.where = where;
    }

    /** Parses JSON or JSONC; {@code where} names the source in errors. */
    public static Object parse(String text, String where) {
        Json p = new Json(text, where);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) throw p.error("unexpected text after the value");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text, String where) {
        Object v = parse(text, where);
        if (!(v instanceof Map)) throw new FormatException(where + ": expected a JSON object");
        return (Map<String, Object>) v;
    }

    private FormatException error(String msg) {
        int line = 1, col = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) {
            if (s.charAt(k) == '\n') {
                line++;
                col = 1;
            } else col++;
        }
        return new FormatException(where + ":" + line + ":" + col + ": " + msg);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else if (s.startsWith("//", i)) {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else if (s.startsWith("/*", i)) {
                int e = s.indexOf("*/", i + 2);
                if (e < 0) throw error("unclosed comment");
                i = e + 2;
            } else break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                while (true) {
                    if (peek('}')) break;
                    if (i >= s.length() || s.charAt(i) != '"') throw error("expected a key");
                    String k = string();
                    ws();
                    expect(':');
                    ws();
                    m.put(k, value());
                    ws();
                    if (peek(',')) {
                        i++;
                        ws();
                        continue;
                    }
                    if (peek('}')) break;
                    throw error("expected , or }");
                }
                i++;
                return m;
            }
            case '[': {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                while (true) {
                    if (peek(']')) break;
                    l.add(value());
                    ws();
                    if (peek(',')) {
                        i++;
                        ws();
                        continue;
                    }
                    if (peek(']')) break;
                    throw error("expected , or ]");
                }
                i++;
                return l;
            }
            case '"':
                return string();
            default:
                if (s.startsWith("true", i)) {
                    i += 4;
                    return Boolean.TRUE;
                }
                if (s.startsWith("false", i)) {
                    i += 5;
                    return Boolean.FALSE;
                }
                if (s.startsWith("null", i)) {
                    i += 4;
                    return null;
                }
                return number();
        }
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected " + c);
        i++;
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("unclosed string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) throw error("unclosed string");
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("bad \\u escape");
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> sb.append(e);
            }
        }
    }

    private Object number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(st, i);
        if (n.isEmpty()) throw error("unexpected character '" + s.charAt(st) + "'");
        try {
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        } catch (NumberFormatException e) {
            throw error("bad number " + n);
        }
    }

    /** Writes compact JSON. */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, -1, 0);
        return sb.toString();
    }

    /** Writes JSON indented by two spaces. */
    public static String pretty(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 2, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int indent, int depth) {
        if (v == null) sb.append("null");
        else if (v instanceof String str) quote(sb, str);
        else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) sb.append(v);
        else if (v instanceof Number n)
            sb.append(n.doubleValue() == Math.rint(n.doubleValue()) ? Long.toString(n.longValue()) : n.toString());
        else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                nl(sb, indent, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(indent < 0 ? ":" : ": ");
                write(sb, e.getValue(), indent, depth + 1);
            }
            nl(sb, indent, depth);
            sb.append('}');
        } else if (v instanceof Iterable<?> l) {
            if (!l.iterator().hasNext()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(',');
                first = false;
                nl(sb, indent, depth + 1);
                write(sb, o, indent, depth + 1);
            }
            nl(sb, indent, depth);
            sb.append(']');
        } else quote(sb, v.toString());
    }

    private static void nl(StringBuilder sb, int indent, int depth) {
        if (indent < 0) return;
        sb.append('\n').append(" ".repeat(indent * depth));
    }

    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        quote(sb, s);
        return sb.toString();
    }
}
