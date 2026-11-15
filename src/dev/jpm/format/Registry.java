package dev.jpm.format;

import dev.jpm.format.api.Context;
import dev.jpm.format.api.Lint;
import dev.jpm.format.api.Rules;
import dev.jpm.format.api.Severity;
import dev.jpm.format.rules.Builtins;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Every rule of a run: the built-in ones, then the project's custom rules. */
public final class Registry {
    public final Rules rules = new Rules();
    private final Set<String> builtin = new LinkedHashSet<>();

    /** A rule the engine itself reports for; its {@link #check} does nothing. */
    record Internal(String id, String category, Severity severity) implements Lint {
        @Override
        public void check(Context ctx) {
        }
    }

    static final String PARSE_ERROR = "ParseError";
    static final String NOT_IDEMPOTENT = "NotIdempotent";
    static final String RULE_CRASH = "RuleCrash";

    private Registry() {
    }

    /** The built-in rules only. */
    public static Registry builtins() {
        Registry r = new Registry();
        Builtins.register(r.rules);
        r.internals();
        for (Lint l : r.rules.all()) r.builtin.add(l.id());
        return r;
    }

    /** The built-in rules, then {@code extra}, for tests. */
    public static Registry builtinsAnd(List<Lint> extra) {
        Registry r = builtins();
        for (Lint l : extra) r.rules.add(l);
        return r;
    }

    /** Exactly {@code rules} (and the engine's own), for tests; all of them count as custom, so they are on. */
    public static Registry of(List<Lint> rules) {
        Registry r = new Registry();
        for (Lint l : rules) r.rules.add(l);
        r.internals();
        for (Lint l : r.rules.all()) if (internal(l)) r.builtin.add(l.id());
        return r;
    }

    private void internals() {
        rules.add(new Internal(PARSE_ERROR, "syntax", Severity.ERROR));
        rules.add(new Internal(Suppressions.INVALID, "suppressions", Severity.ERROR));
        rules.add(new Internal(Suppressions.UNUSED, "suppressions", Severity.WARN));
        rules.add(new Internal(NOT_IDEMPOTENT, "internal", Severity.ERROR));
        rules.add(new Internal(RULE_CRASH, "internal", Severity.ERROR));
    }

    public boolean builtin(Lint rule) {
        return builtin.contains(rule.id());
    }

    static boolean internal(Lint rule) {
        return rule instanceof Internal;
    }

    public Set<String> categories() {
        Set<String> out = new TreeSet<>();
        for (Lint l : rules.all()) out.add(l.category());
        out.add("custom");
        return Collections.unmodifiableSet(out);
    }
}
