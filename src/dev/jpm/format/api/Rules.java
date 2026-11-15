package dev.jpm.format.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ordered list of rules. Formatters run in this order, each on the previous one's output; the order of
 * plain lint rules does not matter. Naming a rule that does not exist is an error, as is registering an id
 * twice.
 */
public final class Rules {
    private final List<Lint> rules = new ArrayList<>();

    /** Appends {@code rule} at the very end. */
    public Rules add(Lint rule) {
        rules.add(checkNew(rule));
        return this;
    }

    /** Places {@code rule} right before the rule {@code ruleId}. */
    public Rules addBefore(String ruleId, Lint rule) {
        rules.add(indexOf(ruleId), checkNew(rule));
        return this;
    }

    /** Places {@code rule} right after the rule {@code ruleId}. */
    public Rules addAfter(String ruleId, Lint rule) {
        rules.add(indexOf(ruleId) + 1, checkNew(rule));
        return this;
    }

    /** The rule with this id, or null. */
    public Lint get(String id) {
        for (Lint r : rules) if (r.id().equals(id)) return r;
        return null;
    }

    /** All rules in order. */
    public List<Lint> all() {
        return Collections.unmodifiableList(rules);
    }

    private int indexOf(String id) {
        for (int i = 0; i < rules.size(); i++) if (rules.get(i).id().equals(id)) return i;
        List<String> ids = rules.stream().map(Lint::id).toList();
        throw new IllegalArgumentException("No rule with id \"" + id + "\"; rules are " + ids);
    }

    private Lint checkNew(Lint rule) {
        String id = rule.id();
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "Bad rule id \"" + id + "\" (" + rule.getClass().getName() + "): use letters, digits, - and _");
        }
        String cat = rule.category();
        if (cat == null || !cat.matches("[a-z][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException("Bad category \"" + cat + "\" for rule " + id);
        }
        if (get(id) != null) throw new IllegalArgumentException("Two rules have the id \"" + id + "\"");
        return rule;
    }
}
