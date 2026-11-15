package dev.jpm.format.api;

import java.util.Locale;

/** How a finding is reported; {@code off} turns a rule off. Config values are lower case. */
public enum Severity {
    OFF,
    WARN,
    ERROR;

    /** Parses {@code off}, {@code warn} or {@code error}; null for anything else. */
    public static Severity parse(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "off" -> OFF;
            case "warn", "warning" -> WARN;
            case "error" -> ERROR;
            default -> null;
        };
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
