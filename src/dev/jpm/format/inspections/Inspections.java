package dev.jpm.format.inspections;

import java.util.ArrayList;
import java.util.List;

/**
 * Every built-in IntelliJ inspection, in the order docs/inspections.md lists them.
 */
public final class Inspections {
    private Inspections() {
    }

    public static List<Inspection> all() {
        List<Inspection> out = new ArrayList<>();
        out.addAll(ControlFlow.all());
        out.addAll(Declarations.all());
        out.addAll(Expressions.all());
        out.addAll(Strings.all());
        out.addAll(Library.all());
        out.addAll(Javadoc.all());
        return List.copyOf(out);
    }
}
