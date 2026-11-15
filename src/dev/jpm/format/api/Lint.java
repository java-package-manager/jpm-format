package dev.jpm.format.api;

import java.util.Set;

/**
 * A rule that reports problems and never changes code.
 *
 * <p>Only {@link #id()} and {@link #check(Context)} are required. Config, suppression comments and
 * {@link Rules#addBefore}/{@link Rules#addAfter} refer to a rule by its id, so pick one that is unique and
 * stable, in PascalCase like IntelliJ's inspection names ({@code StringEquality}).
 */
public interface Lint {
    /** The rule's id, such as {@code UnusedImport}. */
    String id();

    /** The rule's category, such as {@code correctness} or {@code style}; custom rules default to {@code custom}. */
    default String category() {
        return "custom";
    }

    /** The severity used when the config does not set one. */
    default Severity severity() {
        return Severity.WARN;
    }

    /** A link to the rule's documentation, or null. */
    default String docs() {
        return null;
    }

    /**
     * Other names that suppress this rule in {@code @SuppressWarnings} and {@code //noinspection}, such as
     * IntelliJ's {@code unused}. The id always suppresses it.
     */
    default Set<String> aliases() {
        return Set.of();
    }

    /**
     * True if the rule needs type information ({@link Context#types()}). Such rules are skipped, with one
     * warning per run, outside a jpm project or when its dependencies are not installed.
     */
    default boolean typeAware() {
        return false;
    }

    /** Reports problems in {@link Context#source()} with {@link Context#report}. */
    void check(Context ctx);
}
