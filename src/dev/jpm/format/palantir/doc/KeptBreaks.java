package dev.jpm.format.palantir.doc;

import dev.jpm.format.palantir.Output;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * jpm-format: the layout that keeps the input's line breaks, as IntelliJ IDEA does by default
 * ({@code JavaFormatterOptions.keepLineBreaks}). {@link #of} reads the input once: an optional {@link Break} is taken
 * where the input had a newline and never otherwise, and a token or comment with a newline before it but no break
 * starts a line anyway. {@link Level} then lays the code out with these decisions instead of searching for the best
 * fit, keeping the state below, which belongs to one layout on one thread ({@link #apply}).
 *
 * <p>The indent of a wrapped line counts from the line it continues, not from every level around it:
 * {@code foo(bar(a,} then {@code b))} puts {@code b} one continuation indent in, not two.
 */
public final class KeptBreaks {
    private static final ThreadLocal<KeptBreaks> CURRENT = new ThreadLocal<>();

    /** The indent of wrapped lines. */
    final int continuation;

    /** Optional breaks that stand where the input has a newline. */
    private final Set<Break> kept = identitySet();

    /**
     * Forced breaks that a later optional break between the same two tokens stands in for: the innermost one starts
     * the line, at least as far in as the forced one would have (a comment's break is placed outside the levels the
     * next token opens).
     */
    private final Set<Break> replaced = identitySet();

    /** Breaks right before a closing parenthesis, bracket or brace. */
    private final Set<Break> beforeClosing = identitySet();

    /** Tokens and comments with a newline before them in the input but no break. */
    private final Set<Doc> newlineBefore = identitySet();

    /** The {@code }} of empty bodies written on two lines, which goes under the start of its statement. */
    private final Set<Doc> emptyBody = identitySet();

    /** Breaks before, and leaves that are, comments written at the first column, which stay there. */
    private final Set<Doc> firstColumn = identitySet();

    /** Whether each level has a break that is taken or a leaf that starts a line, at any depth. */
    private final Map<Level, Boolean> breaking = new IdentityHashMap<>();

    /** The indent each of {@link #newlineBefore} was laid out at, for {@link #writeNewlineBefore}. */
    private final Map<Doc, Integer> newlineIndents = new IdentityHashMap<>();

    /** The indent of the line being laid out. */
    int lineIndent;

    /** The indent of the line after the last forced break: where the statement or declaration started. */
    int statementLine;

    /** The indents of the lines the open parentheses, brackets and braces are on, innermost first. */
    private final Deque<Integer> opened = new ArrayDeque<>();

    /** The column after the last {@code (}, and the number of lines before it, for {@link Level#ALIGNED}. */
    private int afterParen, afterParenLines;

    /** The column a replaced break would have started its line at, or -1. */
    private int deferred = -1;

    private KeptBreaks(int continuation) {
        this.continuation = continuation;
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /**
     * The decisions for {@code root}, laid out from {@code text}; a line that starts where no break stands is
     * indented {@code continuation} past the line its statement starts on.
     */
    public static KeptBreaks of(Level root, String text, int continuation) {
        KeptBreaks k = new KeptBreaks(continuation);
        k.walk(root, text, new ArrayList<>(), new int[] {-1});
        return k;
    }

    /** Visits the leaves in order; {@code pending} holds the breaks since the last one, which ended at {@code end}. */
    private void walk(Doc doc, String text, List<Break> pending, int[] end) {
        if (doc instanceof Level level) {
            for (Doc d : level.getDocs()) walk(d, text, pending, end);
            return;
        }
        if (doc instanceof Break b) {
            pending.add(b);
            return;
        }
        int start, length;
        if (doc instanceof Token t) {
            start = t.getToken().getTok().getPosition();
            length = t.getToken().getTok().length();
            if (start >= 0 && isClosing(t)) beforeClosing.addAll(pending);
        } else if (doc instanceof Comment c) {
            start = c.tok().getPosition();
            length = c.tok().length();
        } else {
            return;
        }
        if (start < 0) return;
        if (end[0] >= 0 && start >= end[0] && text.substring(end[0], start).indexOf('\n') >= 0) {
            if (pending.isEmpty()) {
                if (!joined(text, end[0], start)) newlineBefore.add(doc);
                if (text.charAt(end[0] - 1) == '{' && text.charAt(start) == '}') emptyBody.add(doc);
            } else {
                Break last = pending.get(pending.size() - 1);
                kept.add(last);
                if (!last.isForced()) for (Break b : pending) if (b.isForced()) replaced.add(b);
            }
            if (doc instanceof Comment c && c.tok().getColumn() == 0 && !c.tok().isJavadocComment()) {
                firstColumn.addAll(pending);
                if (pending.isEmpty()) firstColumn.add(doc);
            }
        }
        pending.clear();
        end[0] = start + length;
    }

    /**
     * Whether a newline in the input between {@code end} and {@code start} is one IntelliJ removes: before the
     * {@code else}, {@code catch}, {@code finally} or {@code while} after a {@code }}.
     */
    private static boolean joined(String text, int end, int start) {
        if (text.charAt(end - 1) != '}') return false;
        for (String word : new String[] {"else", "catch", "finally", "while"}) {
            int after = start + word.length();
            if (text.startsWith(word, start)
                    && (after >= text.length() || !Character.isJavaIdentifierPart(text.charAt(after)))) return true;
        }
        return false;
    }

    static boolean isClosing(Token t) {
        String text = t.getToken().getTok().getOriginalText();
        return text.equals(")") || text.equals("]") || text.equals("}");
    }

    /** Runs {@code layout}, or the writing of its result, with these decisions on this thread. */
    public <T> T apply(Supplier<T> layout) {
        KeptBreaks before = CURRENT.get();
        CURRENT.set(this);
        try {
            return layout.get();
        } finally {
            if (before == null) CURRENT.remove();
            else CURRENT.set(before);
        }
    }

    static KeptBreaks current() {
        return CURRENT.get();
    }

    boolean taken(Break b) {
        return !replaced.contains(b) && (b.isForced() || kept.contains(b));
    }

    /** Whether a later break between the same two tokens stands in for {@code b}. */
    boolean replaced(Break b) {
        return replaced.contains(b);
    }

    /** Whether {@code b} stands right before a closing parenthesis, bracket or brace. */
    boolean beforeClosing(Break b) {
        return beforeClosing.contains(b);
    }

    /** Whether {@code leaf} starts a line though no break stands before it. */
    boolean startsLine(Doc leaf) {
        return newlineBefore.contains(leaf);
    }

    /** Whether {@code leaf} is the {@code }} of an empty body written on two lines. */
    boolean closesEmptyBody(Doc leaf) {
        return emptyBody.contains(leaf);
    }

    /** Whether {@code doc}, a break or a leaf, puts what follows it at the first column. */
    boolean firstColumn(Doc doc) {
        return firstColumn.contains(doc);
    }

    /** Whether {@code level} has a break that is taken or a leaf that starts a line, at any depth. */
    boolean breaks(Level level) {
        Boolean known = breaking.get(level);
        if (known != null) return known;
        boolean any = false;
        for (Doc d : level.getDocs()) {
            if (d instanceof Break b && taken(b) || newlineBefore.contains(d) || d instanceof Level l && breaks(l)) {
                any = true;
                break;
            }
        }
        breaking.put(level, any);
        return any;
    }

    /** Records that {@code leaf}, which {@link #startsLine}, was laid out at {@code indent}. */
    void laidOut(Doc leaf, int indent) {
        newlineIndents.put(leaf, indent);
    }

    /** Writes the newline and indent before {@code leaf}, if it starts a line without a break. */
    static void writeNewlineBefore(Doc leaf, State state, Output output) {
        KeptBreaks k = CURRENT.get();
        Integer indent = k == null ? null : k.newlineIndents.get(leaf);
        if (indent == null) return;
        output.append(state, "\n", Doc.EMPTY_RANGE);
        output.indent(indent);
    }

    /** Keeps track of the bracket {@code leaf} opens or closes, on the line indented {@link #lineIndent}. */
    void bracket(Doc leaf) {
        if (!(leaf instanceof Token t)) return;
        String text = t.getToken().getTok().getOriginalText();
        if (text.equals("(") || text.equals("[") || text.equals("{")) opened.push(lineIndent);
        else if (isClosing(t) && !opened.isEmpty()) opened.pop();
    }

    /** Keeps track of the brackets in {@code doc}, laid out on one line. */
    void brackets(Doc doc) {
        if (doc instanceof Level l) for (Doc d : l.getDocs()) brackets(d);
        else bracket(doc);
    }

    /** The indent of the line the innermost open bracket is on, or {@code fallback}. */
    int openerLine(int fallback) {
        return opened.isEmpty() ? fallback : opened.peek();
    }

    void afterParen(int column, int lines) {
        afterParen = column;
        afterParenLines = lines;
    }

    /**
     * The column an {@link Level#ALIGNED} level that starts after {@code lines} lines lines up with: right after its
     * {@code (}, or the indent of its line when it starts on a later one.
     */
    int alignment(int lines) {
        return lines > afterParenLines ? lineIndent : afterParen;
    }

    /** Remembers the column a replaced break would have started its line at. */
    void defer(int column) {
        deferred = Math.max(deferred, column);
    }

    /** The column remembered by {@link #defer}, or -1, and forgets it. */
    int takeDeferred() {
        int d = deferred;
        deferred = -1;
        return d;
    }
}
