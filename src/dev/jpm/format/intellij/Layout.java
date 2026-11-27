package dev.jpm.format.intellij;

import dev.jpm.format.FileContext;
import dev.jpm.format.FormatException;
import dev.jpm.format.Source;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * The layout engine of the IntelliJ style (ADR 0006): IntelliJ IDEA's default Java code style, rewriting only the
 * whitespace between tokens. Line breaks stay where the code has them, except where IntelliJ always breaks or joins
 * lines: one statement or member per line, block contents on lines of their own, declaration annotations on lines
 * of their own (not a type-use nullness annotation IntelliJ knows, right before the type on its line),
 * {@code {} at the end of the line it opens from, and {@code } else}, {@code } catch},
 * {@code } finally} and {@code } while}.
 *
 * <p>Indents follow IntelliJ's model: a block's contents are one indent in from the line its statement starts on,
 * a lambda's or array initializer's from the line the brace is on, an anonymous class's from the line its
 * {@code new} is on. A line that continues
 * something is one continuation indent in from the nearest enclosing expression or declaration that starts a line
 * (a statement, an argument, a chain or an operand of its own); arguments are one continuation indent in from the
 * call, or from the line its {@code .} starts. Method parameters, {@code for} headers, try resources and record
 * components line up after their parenthesis when the first one is on its line. Statement bodies without braces and
 * switch rule bodies move to the next line when they would end past {@code lineWidth}. Blank lines are kept up to
 * {@code maxBlankLines}; the IntelliJ rules after the engine handle the rest.
 */
public final class Layout {
    private static final Set<String> SPACED_KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "synchronized",
            "try");

    private static final int ROOT = 0, BLOCK = 1, SWITCH = 2, ARRAY = 3, PAREN = 4, BRACKET = 5;

    private static final int PENDING = -2;

    private Layout() {
    }

    /**
     * The file formatted, with {@code \n} or {@code \r\n} as the file has them.
     */
    public static String format(FileContext ctx) {
        return format(ctx, 0, ctx.source().length());
    }

    /**
     * The file with only the lines that touch {@code [start, end)} formatted; the rest is left as it is. The layout of
     * those lines is worked out as for the whole file, with the lines around them as they are.
     */
    public static String format(FileContext ctx, int start, int end) {
        if (!ctx.parsed().errors().isEmpty()) throw new FormatException(ctx.path() + ": does not parse");
        var s = ctx.settings();
        String text = ctx.source();
        int from = text.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
        if (start == 0) from = 0;
        int to = text.indexOf('\n', Math.max(start, end - 1));
        to = to < 0 ? text.length() + 1 : to + 1;
        return new Run(text, Syntax.of(ctx, text), s.indentWidth(), s.continuationIndentWidth(), s.maxBlankLines(),
                s.lineWidth(), from, to).format();
    }

    private static final class Frame {
        int kind;
        int base;
        int align = -1;
        int itemIndent;
        boolean expectItem;
        boolean enumBody;
        int opener = -1;
        boolean shiftItems;
    }

    private static final class Run {
        private final String text;
        private final Syntax syn;
        private final List<Tokens.Token> toks;
        private final int indent;
        private final int continuation;
        private final int maxBlank;
        private final int width;
        private final String newline;
        private final int[] lines;
        private final int[] lineIndent;
        private final int[] columns;
        private final Deque<Frame> frames = new ArrayDeque<>();
        private final StringBuilder out = new StringBuilder();
        private int column;
        private int current;
        private int prevCode = -1;
        private int line;
        private final int[] tokenLine;
        private final int from;
        private final int to;

        Run(String text, Syntax syn, int indent, int continuation, int maxBlank, int width, int from, int to) {
            this.from = from;
            this.to = to;
            this.width = width;
            this.text = text;
            this.syn = syn;
            this.toks = syn.tokens;
            this.indent = indent;
            this.continuation = continuation;
            this.maxBlank = maxBlank;
            this.newline = Source.newline(text);
            this.lines = new int[toks.size()];
            this.lineIndent = new int[toks.size()];
            this.columns = new int[toks.size()];
            this.tokenLine = new int[toks.size()];
            Arrays.fill(columns, -1);
            Frame root = new Frame();
            root.kind = ROOT;
            root.base = -indent;
            frames.push(root);
        }

        String format() {
            breaks();
            for (int i = 0; i < toks.size(); i++) emit(i);
            if (!toks.isEmpty() && toks.getLast().start() >= to) {
                out.append(text.substring(toks.getLast().end()).replace("\r", ""));
            } else {
                stripTrailing();
                if (!toks.isEmpty()) {
                    out.append('\n');
                    String tail = text.substring(Math.min(text.length(), toks.getLast().end()));
                    int breaks = (int) tail.chars().filter(c -> c == '\n').count();
                    out.append("\n".repeat(Math.max(0, Math.min(breaks - 1, maxBlank))));
                }
            }
            String result = out.toString();
            return newline.equals("\n") ? result : result.replace("\n", newline);
        }

        private void breaks() {
            for (int i = 0; i < toks.size(); i++) {
                if (i == 0) {
                    lines[i] = 0;
                    continue;
                }
                Tokens.Token prev = toks.get(i - 1);
                int n = Math.min(newlines(prev.end(), toks.get(i).start()), maxBlank + 1);
                if (syn.breakBefore[i] && n == 0 && !leadingComment(i - 1)) n = 1;
                if (syn.blankBefore[i]) n = Math.max(n, 2);
                Tokens.Token t = toks.get(i);
                if (t.op("}") && syn.match[i] >= 0 && syn.brace[syn.match[i]] != Syntax.ARRAY
                        && syn.brace[syn.match[i]] != Syntax.SIMPLE_ENUM && hasCode(syn.match[i], i)) {
                    n = Math.max(n, 1);
                }
                if (prev.kind() == Tokens.Kind.LINE_COMMENT) n = Math.max(n, 1);
                else if (!prev.comment() && join(i)) n = 0;
                lines[i] = n;
            }
        }

        private boolean leadingComment(int k) {
            if (toks.get(k).kind() != Tokens.Kind.BLOCK_COMMENT) return false;
            return k == 0 || newlines(toks.get(k - 1).end(), toks.get(k).start()) > 0;
        }

        private boolean fits(int i) {
            int k = toks.get(i).start();
            int end = text.indexOf('\n', k);
            String rest = text.substring(k, end < 0 ? text.length() : end).strip();
            return column + 1 + rest.length() <= width;
        }

        private boolean join(int i) {
            if (syn.joinBefore[i] || syn.joinIfFits[i]) return true;
            Tokens.Token t = toks.get(i);
            if (!t.op("{") || syn.start[i] != 0) return false;
            byte b = syn.brace[i];
            if (b == Syntax.SIMPLE_ENUM) return true;
            return b != Syntax.ARRAY && b != 0;
        }

        private boolean hasCode(int from, int to) {
            for (int k = from + 1; k < to; k++) if (!toks.get(k).comment()) return true;
            return false;
        }

        private int newlines(int from, int to) {
            int n = 0;
            for (int k = from; k < to; k++) if (text.charAt(k) == '\n') n++;
            return n;
        }

        private int originalColumn(int offset) {
            int k = offset;
            while (k > 0 && text.charAt(k - 1) != '\n') k--;
            return offset - k;
        }

        private void emit(int i) {
            Tokens.Token t = toks.get(i);
            Frame f = frames.peek();
            boolean outside = t.start() < from || t.start() >= to;
            if (outside) {
                String gap = i == 0 ? text.substring(0, t.start()) : text.substring(toks.get(i - 1).end(), t.start());
                gap = gap.replace("\r", "");
                lines[i] = (int) gap.chars().filter(c -> c == '\n').count();
                line += lines[i];
                out.append(gap);
                if (i == 0 || lines[i] > 0) current = originalColumn(t.start());
                column = out.length() - out.lastIndexOf("\n") - 1;
            } else if (i > 0 && lines[i] == 0 && !fits(i)) {
                boolean body = syn.start[i] == Syntax.BODY && syn.owner[i] >= 0
                        && !(t.word("if") && toks.get(before(i)).word("else"));
                if (body || syn.joinIfFits[i]) lines[i] = 1;
            }
            if (!outside && (lines[i] > 0 || i == 0)) {
                int ind = i == 0 && t.comment() ? 0 : indentFor(i);
                if (i > 0) {
                    stripTrailing();
                    out.append("\n".repeat(lines[i]));
                    line += lines[i];
                }
                out.append(" ".repeat(Math.max(0, ind)));
                current = Math.max(0, ind);
                column = current;
            } else if (!outside) {
                int sp = space(i);
                out.append(" ".repeat(sp));
                column += sp;
            }
            lineIndent[i] = current;
            columns[i] = column;
            tokenLine[i] = line;
            if (!t.comment() && f.kind == PAREN && f.align == PENDING) {
                f.align = lines[i] > 0 || t.op(";") ? -1 : column;
            }
            if (outside) {
                String raw = t.text().replace("\r", "");
                out.append(raw);
                line += (int) raw.chars().filter(c -> c == '\n').count();
                column = raw.indexOf('\n') < 0 ? column + raw.length() : raw.length() - raw.lastIndexOf('\n') - 1;
            } else {
                write(t);
            }
            if (t.comment()) return;
            if (itemStart(i, f)) f.itemIndent = current;
            if (f.kind == PAREN || f.kind == BRACKET || f.kind == ARRAY) {
                f.expectItem = t.op(",") || (f.kind == PAREN && t.op(";"));
            }
            if (t.kind() == Tokens.Kind.OP) {
                switch (t.text()) {
                    case "{" -> open(i, f);
                    case "(", "[" -> {
                        Frame p = new Frame();
                        p.kind = t.is("(") ? PAREN : BRACKET;
                        p.base = t.is("(") ? parenBase(i, f) : current;
                        p.shiftItems = t.is("(") && wrappedChain(i);
                        p.opener = i;
                        p.align = syn.align[i] ? PENDING : -1;
                        p.itemIndent = current;
                        p.expectItem = true;
                        frames.push(p);
                    }
                    case "}" -> close(BLOCK, SWITCH, ARRAY);
                    case ")" -> close(PAREN, PAREN, PAREN);
                    case "]" -> close(BRACKET, BRACKET, BRACKET);
                    default -> {
                    }
                }
            }
            prevCode = i;
        }

        private int lineStartingAncestor(int i, int after) {
            int n = syn.inner[i];
            while (n >= 0) {
                int st = syn.nodeStart[n];
                if (st <= after) return -1;
                if (st < i && (st == 0 || lines[st] > 0)) return st;
                n = syn.nodeParent[n];
            }
            return -1;
        }

        private int parenBase(int i, Frame f) {
            int dot = chainDot(i);
            if (dot >= 0) return lineIndent[dot];
            int call = -1;
            int binary = -1;
            boolean inside = true;
            for (int n = syn.inner[i]; n >= 0; n = syn.nodeParent[n]) {
                int st = syn.nodeStart[n];
                if (st > i) continue;
                if (call < 0) call = st;
                if (inside && st <= f.opener) {
                    inside = false;
                    if (binary >= 0) return lineIndent[binary];
                    if (f.opener >= 0 && tokenLine[call] != tokenLine[f.opener]) return lineIndent[call];
                    if (f.kind == PAREN && f.opener >= 0) return f.base;
                }
                if (st == 0 || lines[st] > 0) return lineIndent[binary >= 0 ? binary : st];
                if (syn.nodeBinary[n]) binary = st;
            }
            if (binary >= 0) return lineIndent[binary];
            return call >= 0 ? lineIndent[call] : current;
        }

        private boolean wrappedChain(int i) {
            int name = before(i);
            if (chainDot(i) >= 0 || name < 0 || toks.get(name).kind() != Tokens.Kind.WORD) return false;
            int dot = before(name);
            if (dot < 0 || !toks.get(dot).op(".") || syn.match[i] < 0) return false;
            int next = syn.match[i] + 1;
            while (next < toks.size() && toks.get(next).comment()) next++;
            return next < toks.size() && toks.get(next).op(".") && lines[next] > 0;
        }

        private int chainDot(int i) {
            int k = before(i);
            if (k < 0 || toks.get(k).kind() != Tokens.Kind.WORD || SPACED_KEYWORDS.contains(toks.get(k).text())) {
                return -1;
            }
            while (true) {
                int p = before(k);
                if (p < 0 || !toks.get(p).op(".")) return -1;
                if (lines[p] > 0) return p;
                int q = before(p);
                if (q < 0) return -1;
                Tokens.Token qt = toks.get(q);
                if ((qt.op(")") || qt.op("]")) && syn.match[q] >= 0) {
                    int o = before(syn.match[q]);
                    if (o < 0 || toks.get(o).kind() != Tokens.Kind.WORD) return -1;
                    k = o;
                } else if (qt.kind() == Tokens.Kind.WORD) {
                    k = q;
                } else {
                    return -1;
                }
            }
        }

        private int before(int i) {
            int k = i - 1;
            while (k >= 0 && toks.get(k).comment()) k--;
            return k;
        }

        private void open(int i, Frame f) {
            byte b = syn.brace[i];
            Frame n = new Frame();
            n.kind = b == Syntax.ARRAY ? ARRAY : b == Syntax.SWITCH || b == Syntax.SWITCH_EXPRESSION ? SWITCH : BLOCK;
            n.enumBody = b == Syntax.ENUM || b == Syntax.SIMPLE_ENUM;
            boolean expression = b == Syntax.LAMBDA || b == Syntax.ANONYMOUS || b == Syntax.SWITCH_EXPRESSION
                    || b == Syntax.ARRAY || f.kind == PAREN || f.kind == BRACKET || f.kind == ARRAY;
            n.base = expression ? current : f.itemIndent;
            if (b == Syntax.ANONYMOUS) {
                int k = newOf(i);
                if (k >= 0) n.base = lineIndent[k];
            }
            n.itemIndent = n.base + indent;
            n.expectItem = true;
            n.opener = i;
            frames.push(n);
        }

        private int newOf(int brace) {
            int k = before(brace);
            if (k >= 0 && toks.get(k).op(")") && syn.match[k] >= 0) k = before(syn.match[k]);
            int depth = 0;
            for (; k >= 0; k = before(k)) {
                Tokens.Token t = toks.get(k);
                if (t.op(">")) depth++;
                else if (t.op("<")) depth--;
                else if (t.op(">>")) depth += 2;
                else if (depth == 0 && t.is("new")) return k;
                else if (depth == 0 && t.kind() != Tokens.Kind.WORD && !t.op(".") && !t.op("@")) return -1;
            }
            return -1;
        }

        private void close(int a, int b, int c) {
            while (frames.size() > 1) {
                Frame f = frames.pop();
                if (f.kind == a || f.kind == b || f.kind == c) return;
            }
        }

        private boolean itemStart(int i, Frame f) {
            return switch (f.kind) {
                case ROOT, BLOCK, SWITCH -> {
                    byte s = syn.start[i];
                    if (s != 0 && s != Syntax.ANNOTATED) yield true;
                    yield f.enumBody && toks.get(i).op(";") && prevCode >= 0 && toks.get(prevCode).op(",");
                }
                default -> f.expectItem;
            };
        }

        private int indentFor(int i) {
            Frame f = frames.peek();
            Tokens.Token t = toks.get(i);
            if (t.comment()) {
                boolean firstColumn = !t.text().startsWith("/**") && originalColumn(t.start()) == 0;
                if (firstColumn) return 0;
                int j = i + 1;
                while (j < toks.size() && toks.get(j).comment()) j++;
                if (j >= toks.size()) return child(f);
                if (closes(toks.get(j), f)) return child(f);
                if (f.kind == SWITCH && syn.start[j] == Syntax.CASE && prevCode >= 0 && !toks.get(prevCode).op("{")
                        && (syn.role[prevCode] == Syntax.CASE_COLON
                        || originalColumn(t.start()) > originalColumn(toks.get(j).start()))) {
                    return f.base + 2 * indent;
                }
                return indentFor(j);
            }
            if (closes(t, f)) return f.base;
            int o = syn.owner[i];
            if (o >= 0 && prevCode >= 0 && toks.get(prevCode).op("->")) return lineIndent[o] + continuation;
            if (o >= 0 && syn.start[i] != Syntax.BODY) return lineIndent[o];
            if (syn.alignTo[i] >= 0 && columns[syn.alignTo[i]] >= 0) return columns[syn.alignTo[i]];
            return switch (f.kind) {
                case ROOT, BLOCK, SWITCH -> switch (syn.start[i]) {
                    case Syntax.CASE -> f.base + indent;
                    case Syntax.CASE_STATEMENT -> f.base + 2 * indent;
                    case Syntax.STATEMENT, Syntax.ENUM_CONSTANT ->
                            f.kind == SWITCH ? f.base + 2 * indent : f.base + indent;
                    case Syntax.BODY -> (o >= 0 ? lineIndent[o] : f.itemIndent) + indent;
                    case Syntax.ANNOTATED -> f.itemIndent;
                    default -> {
                        if (f.enumBody && t.op(";")) yield f.base + indent;
                        int a = lineStartingAncestor(i, f.opener);
                        yield a >= 0 ? lineIndent[a] + continuation : f.itemIndent + continuation;
                    }
                };
                default -> {
                    if (f.expectItem) {
                        yield f.align >= 0 ? f.align : f.base + continuation + (f.shiftItems ? continuation : 0);
                    }
                    int a = lineStartingAncestor(i, f.opener);
                    if (a >= 0) yield lineIndent[a] + continuation;
                    yield f.align >= 0 ? f.align + continuation : f.base + continuation + (f.shiftItems ? continuation : 0);
                }
            };
        }

        private int child(Frame f) {
            return switch (f.kind) {
                case ROOT -> 0;
                case BLOCK, SWITCH -> f.base + indent;
                default -> f.align >= 0 ? f.align : f.base + continuation;
            };
        }

        private static boolean closes(Tokens.Token t, Frame f) {
            return switch (f.kind) {
                case BLOCK, SWITCH, ARRAY -> t.op("}");
                case PAREN -> t.op(")");
                case BRACKET -> t.op("]");
                default -> false;
            };
        }

        private void stripTrailing() {
            int k = out.length();
            while (k > 0 && (out.charAt(k - 1) == ' ' || out.charAt(k - 1) == '\t')) k--;
            out.setLength(k);
        }

        private void write(Tokens.Token t) {
            String s = t.text();
            if (s.indexOf('\n') < 0) {
                out.append(t.kind() == Tokens.Kind.LINE_COMMENT ? s.stripTrailing() : s);
                column += s.length();
                return;
            }
            String[] parts = s.replace("\r", "").split("\n", -1);
            StringBuilder b = new StringBuilder(parts[0].stripTrailing());
            if (t.kind() == Tokens.Kind.TEXT_BLOCK) {
                textBlock(parts, b);
            } else {
                int start = column;
                int delta = start - originalColumn(t.start());
                boolean javadoc = s.startsWith("/**");
                for (int k = 1; k < parts.length; k++) {
                    String p = parts[k];
                    String stripped = p.strip();
                    b.append('\n');
                    if (javadoc && !stripped.startsWith("*")) {
                        b.append(" ".repeat(start + 1)).append(stripped.isEmpty() ? "*" : "* " + stripped);
                        continue;
                    }
                    if (stripped.isEmpty()) continue;
                    if (stripped.startsWith("*")) {
                        b.append(" ".repeat(start + 1)).append(stripped);
                    } else {
                        int lead = p.length() - p.stripLeading().length();
                        b.append(" ".repeat(Math.max(0, lead + delta))).append(p.stripLeading().stripTrailing());
                    }
                }
            }
            String w = b.toString();
            out.append(w);
            line += (int) w.chars().filter(c -> c == '\n').count();
            column = w.length() - w.lastIndexOf('\n') - 1;
        }

        private void textBlock(String[] parts, StringBuilder b) {
            int min = Integer.MAX_VALUE;
            for (int k = 1; k < parts.length; k++) {
                String p = parts[k];
                if (p.isBlank() && k < parts.length - 1) continue;
                int lead = p.length() - p.stripLeading().length();
                if (p.substring(0, lead).indexOf('\t') >= 0) min = -1;
                if (min >= 0) min = Math.min(min, lead);
            }
            int delta = min < 0 ? 0 : current + continuation - min;
            for (int k = 1; k < parts.length; k++) {
                String p = parts[k];
                b.append('\n');
                if (p.isBlank() && k < parts.length - 1) {
                    if (!p.isEmpty())
                        b.append(min < 0 || p.indexOf('\t') >= 0 ? p : " ".repeat(Math.max(0, p.length() + delta)));
                    continue;
                }
                if (min < 0) {
                    b.append(p);
                } else {
                    int lead = p.length() - p.stripLeading().length();
                    b.append(" ".repeat(lead + delta)).append(p.stripLeading());
                }
            }
        }

        private int space(int i) {
            Tokens.Token b = toks.get(i);
            Tokens.Token prev = toks.get(i - 1);
            int original = toks.get(i).start() - prev.end();
            if (b.comment()) return original;
            if (prev.comment()) {
                if (original > 0) return 1;
                return prevCode >= 0 ? rule(prevCode, i) : 0;
            }
            return rule(i - 1, i);
        }

        private int rule(int ia, int ib) {
            Tokens.Token a = toks.get(ia), b = toks.get(ib);
            byte ra = syn.role[ia], rb = syn.role[ib];
            String x = a.text(), y = b.text();
            if (nonSealed(ia) || nonSealed(ib)) return 0;
            boolean aop = a.kind() == Tokens.Kind.OP, bop = b.kind() == Tokens.Kind.OP;
            if (bop && y.equals("}") && syn.match[ib] >= 0
                    && (syn.brace[syn.match[ib]] == Syntax.ARRAY || syn.brace[syn.match[ib]] == Syntax.SIMPLE_ENUM)) {
                return 0;
            }
            if (aop && x.equals("{") && syn.brace[ia] == Syntax.SIMPLE_ENUM) return 0;
            if (aop && x.equals(";")) return 1;
            if (bop && (y.equals(",") || y.equals(";"))) {
                if (rb == Syntax.EMPTY_STATEMENT) return Math.min(1, b.start() - a.end());
                return 0;
            }
            if (aop && x.equals(",")) return 1;
            if (bop && (y.equals(")") || y.equals("]"))) return 0;
            if (aop && (x.equals("(") || x.equals("["))) return 0;
            if (aop && (x.equals(".") || x.equals("::")) || bop && (y.equals(".") || y.equals("::"))) return 0;
            if (bop && y.equals("...")) return 0;
            if (aop && x.equals("...")) return 1;
            if (aop && x.equals("@")) return 0;
            if (bop && y.equals("[")) return endsAnnotation(ia) ? 1 : 0;
            if (rb == Syntax.POSTFIX) return 0;
            if (rb == Syntax.CASE_COLON || rb == Syntax.LABEL_COLON) return 0;
            if (bop && y.equals("{")) {
                if (syn.brace[ib] == Syntax.ARRAY) {
                    if (aop && (x.equals("]") || x.equals("(") || x.equals("{"))) return 0;
                }
                return 1;
            }
            if (aop && x.equals("{")) {
                if (syn.brace[ia] == Syntax.ARRAY) return 0;
                return bop && y.equals("}") ? 0 : 1;
            }
            if (bop && y.equals("}")) {
                int o = syn.match[ib];
                if (o >= 0 && syn.brace[o] == Syntax.ARRAY) return 0;
                return aop && x.equals("{") ? 0 : 1;
            }
            ra = folded(ia, ra);
            rb = folded(ib, rb);
            if (ra == Syntax.PREFIX) {
                if (rb == Syntax.PREFIX && merges(x, y)) return 1;
                return 0;
            }
            if (bop && y.equals("(") && syn.start[ib] == Syntax.BODY) return 1;
            if (bop && y.equals("(")) {
                if (a.kind() == Tokens.Kind.WORD) {
                    if (SPACED_KEYWORDS.contains(x)) return 1;
                    if (x.equals("this") || x.equals("super")) return 0;
                    return KEYWORDS.contains(x) ? 1 : 0;
                }
                if (ra == Syntax.CAST_CLOSE) return 1;
                if (aop && (x.equals(")") || x.equals(">") && ra != Syntax.BIN || x.equals("]"))) return 0;
            }
            if (ra == Syntax.TYPE_ARGS_CLOSE) return 0;
            if (bop && y.equals("<") && rb != Syntax.BIN) {
                return a.kind() == Tokens.Kind.WORD && !KEYWORDS.contains(x) ? 0 : 1;
            }
            if (aop && x.equals("<") && ra != Syntax.BIN) return 0;
            if (bop && y.equals(">") && rb != Syntax.BIN) return 0;
            if (bop && y.equals("?") && rb != Syntax.BIN && aop && x.equals("<")) return 0;
            if (ra == Syntax.CASE_COLON || ra == Syntax.LABEL_COLON) return 1;
            if (bop && spaced(y, rb) || aop && spaced(x, ra)) return 1;
            if (aop && x.equals(">") && ra != Syntax.BIN) {
                return bop && !y.equals("@") && !y.equals("?") && !y.equals("{") ? 0 : 1;
            }
            if (aop && x.equals("?")) return 1;
            if (aop && x.equals("}")) return 1;
            if (aop && x.equals(")")) return 1;
            if (aop && x.equals("]")) return 1;
            if (bop && rb == Syntax.PREFIX) return 1;
            if (!aop && !bop) return 1;
            if (bop && y.equals("@")) return 1;
            return Math.min(1, b.start() - a.end());
        }

        private boolean nonSealed(int i) {
            return toks.get(i).op("-") && syn.role[i] != Syntax.BIN && i > 0 && i + 1 < toks.size()
                    && toks.get(i - 1).is("non") && toks.get(i + 1).is("sealed");
        }

        private boolean endsAnnotation(int i) {
            if (toks.get(i).op(")") && syn.match[i] > 0) i = syn.match[i] - 1;
            while (i >= 0 && toks.get(i).kind() == Tokens.Kind.WORD) {
                if (i > 0 && toks.get(i - 1).op("@")) return true;
                if (i < 2 || !toks.get(i - 1).op(".")) return false;
                i -= 2;
            }
            return false;
        }

        private byte folded(int i, byte role) {
            Tokens.Token t = toks.get(i);
            if (role != 0 || t.kind() != Tokens.Kind.OP || !unary(t.text())) return role;
            boolean sign = t.is("-") || t.is("+");
            return sign && afterOperand(i) ? Syntax.BIN : Syntax.PREFIX;
        }

        private static boolean unary(String x) {
            return x.equals("-") || x.equals("+") || x.equals("!") || x.equals("~");
        }

        private boolean afterOperand(int i) {
            int k = before(i);
            if (k < 0) return false;
            Tokens.Token t = toks.get(k);
            if (t.kind() == Tokens.Kind.OP) {
                return t.is(")") && syn.role[k] != Syntax.CAST_CLOSE || t.is("]") || syn.role[k] == Syntax.POSTFIX;
            }
            return t.kind() != Tokens.Kind.WORD || !KEYWORDS.contains(t.text()) || t.is("this") || t.is("super");
        }

        private static boolean merges(String x, String y) {
            return x.endsWith("-") && y.startsWith("-") || x.endsWith("+") && y.startsWith("+");
        }

        private static boolean spaced(String op, byte role) {
            if (role == Syntax.BIN) return true;
            return switch (op) {
                case "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=", "==", "!=", "<=",
                     ">=", "&&", "||", "->", "|", "&", "^", ":" -> true;
                default -> false;
            };
        }

        private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
                "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
                "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
                "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short",
                "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
                "try", "void", "volatile", "while", "yield", "sealed", "permits");

    }
}
