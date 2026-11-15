package dev.jpm.format.api;

/**
 * Registers rules. A file in the project's {@code rules/} folder can be a {@link Lint} or {@link Formatter}
 * (added with {@link Rules#add}) or a RuleSet, to place formatters with {@link Rules#addBefore} and
 * {@link Rules#addAfter}.
 *
 * <p>A dependency can ship rule sets for several projects, like ESLint plugins: list the class in
 * {@code META-INF/services/dev.jpm.format.api.RuleSet} (in jpm: {@code src/assets/META-INF/services/}).
 */
public interface RuleSet {
    void register(Rules rules);
}
