package dev.jpm.format.intellij;

import dev.jpm.format.api.Formatter;

import java.util.Locale;

/**
 * A rule that adjusts the layout engine's output toward IntelliJ IDEA's default Java code style. These rules run
 * right after {@code JavaFormat} and only change whitespace. They are on with the formatter, unless
 * {@code "formatter": {"style": "palantir"}} asks for Palantir Java Format's output as it is.
 */
public interface IntelliJRule extends Formatter {
    String DOCS = "https://github.com/java-package-manager/jpm-format/blob/main/docs/rules.md#";

    @Override
    default String category() {
        return "format";
    }

    @Override
    default String docs() {
        return DOCS + id().toLowerCase(Locale.ROOT);
    }
}
