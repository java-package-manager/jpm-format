package dev.jpm.format.api;

/**
 * One reported problem. Offsets are character offsets into the source; lines and columns start at 1.
 *
 * @param fix a fix, or null
 */
public record Finding(
        String rule,
        String category,
        Severity severity,
        String path,
        int start,
        int end,
        int line,
        int column,
        int endLine,
        int endColumn,
        String message,
        Fix fix) {

    /** {@code lint/<category>/<rule>}, the name used in suppression comments and reports. */
    public String qualifiedRule() {
        return "lint/" + category + "/" + rule;
    }

    public Finding withSeverity(Severity s) {
        return new Finding(rule, category, s, path, start, end, line, column, endLine, endColumn, message, fix);
    }

    /** {@code 3:5 style/UnusedImport Unused import 'java.util.List'}, as in {@code .findings} files. */
    public String brief() {
        return line + ":" + column + " " + category + "/" + rule + " " + message;
    }
}
