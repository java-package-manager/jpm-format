package dev.jpm.format.api;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;

import java.util.Map;
import java.util.Optional;

/**
 * What a rule sees of one file: its source, its syntax tree, and a way to report problems.
 *
 * <p>The tree is javac's ({@code com.sun.source.tree}), the same tree the formatter works on. Walk it with
 * {@link com.sun.source.util.TreeScanner} or {@link com.sun.source.util.TreePathScanner}.
 */
public interface Context {
    /** The file's path relative to the project root, with {@code /} separators. */
    String path();

    /** The file's current source; for a formatter, the previous formatter's output. */
    String source();

    /** The parsed source. Rules never see a file that does not parse. */
    CompilationUnitTree tree();

    /** Start offset of {@code node} in {@link #source()}. */
    int start(Tree node);

    /** End offset (exclusive) of {@code node} in {@link #source()}. */
    int end(Tree node);

    /** 1-based line of an offset. */
    int line(int offset);

    /** 1-based column of an offset. */
    int column(int offset);

    /** Offset of the start of a 1-based line. */
    int lineStart(int line);

    /**
     * Type information for the file, when the project's classpath is known. Its tree is a separate, attributed
     * parse of the same source, with the same offsets. Rules that need it declare {@link Lint#typeAware()}.
     */
    Optional<TypeInfo> types();

    /** The rule's options from the config: {@code "Rule": {"level": "warn", "options": {...}}}. */
    Map<String, Object> options();

    /** Reports a problem at {@code node}. */
    default void report(Tree node, String message) {
        report(start(node), end(node), message, null);
    }

    /** Reports a problem at {@code node} with a fix. */
    default void report(Tree node, String message, Fix fix) {
        report(start(node), end(node), message, fix);
    }

    /** Reports a problem at {@code [start, end)}, with an optional fix. */
    void report(int start, int end, String message, Fix fix);
}
