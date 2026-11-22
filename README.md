# jpm-format

`jpm format` for [jpm](https://github.com/java-package-manager/jpm): one tool that formats and lints Java,
with an opinionated IntelliJ-style default and your own rules.

```sh
jpm add -g github:java-package-manager/jpm-format   # needs jpm 0.7.1 or newer
jpm format            # format every file and apply every safe fix
jpm format --check    # change nothing; report problems and exit 1 if there are any (for CI)
```

It is a jpm extension: jpm runs `jpm format` as `jpm-format`. It works like Spotless: `jpm format` is
`spotlessApply`, `jpm format --check` is `spotlessCheck`.

## Commands

| Command | |
|---|---|
| `jpm format [paths…]` | Formats every file and applies every safe fix, such as removing unused imports. Lint findings that remain are printed. |
| `jpm format --check [paths…]` | Changes nothing. Reports formatting differences and lint findings together and exits 1 if there are any. |
| `jpm format --stdin <path>` | Formats standard input as if it were `<path>` and prints the result (for editor integrations). |
| `jpm format lsp` | Runs the language server over stdio (also `jpm-format lsp`). |
| `jpm format test [--update]` | Runs the rule tests in `rules/test/` (see [Testing rules](#testing-rules)). |
| `jpm format rules` | Lists every rule, its category and level. |

Options: `--reporter pretty|json|sarif|github`, `--debug` (runs every formatter twice and reports any whose
output changes), `--no-cache`, `--threads <n>`.

Exit codes: 0 when clean, 1 for findings (`--check`) or files that do not parse, 2 for a bad config or option.

Safe fixes are only ones that cannot change behaviour, and they are applied by formatters. Riskier findings
are reported, with their fix offered in the editor and in the JSON and SARIF reports, but never applied
automatically.

## Formatting style

IntelliJ IDEA's default Java code style. The layout comes from jpm-format's fork of
[Palantir Java Format](https://github.com/palantir/palantir-java-format) (Apache 2.0, itself a fork of
google-java-format), whose output is already close to it: 120 columns, 4-space indents, 8-space continuation
indents. The IntelliJ rules (`src/dev/jpm/format/intellij/`) then adjust it where IntelliJ's defaults differ:

- blank lines the code has are kept, up to 2 (also before a closing brace), where Palantir keeps 1;
- methods, nested classes and initializers get a blank line around them, also when they start with a comment;
- empty methods, classes and blocks are written `{` and `}` on two lines; lambdas and anonymous classes keep `{}`.

`"formatter": {"style": "palantir"}` turns them off, for Palantir Java Format's output as it is. Imports follow
IntelliJ's layout: all other imports, then `java.*` and `javax.*`, then static imports, each group sorted
alphabetically and separated by a blank line. Javadoc is not reflowed.

## Configuration

Opinionated defaults; nearly everything can be overridden. The shape follows Biome, with Prettier's option
names. It lives in `project.jsonc` under `extensions.format`, or in a separate `jpm-format.jsonc` at the
project root (not both). In a workspace, a module's own `project.jsonc` applies to the files under it.

```jsonc
{
  "extensions": {
    "format": {
      "extends": ["config/format.jsonc"],          // shared configs, merged in order, then this one
      "ignore": ["src/generated/**"],              // .cache, dist, build, target, .git … are always ignored
      "formatter": {
        "enabled": true,
        "style": "intellij",                       // or "palantir": Palantir Java Format's output as it is
        "lineWidth": 120,                          // 20 to 1000
        "indentWidth": 4,                          // 1 to 16
        "continuationIndentWidth": 8,              // wrapped lines; twice indentWidth by default, as in IntelliJ
        "maxBlankLines": 2,                        // blank lines kept in a row, 1 to 10 (1: as Palantir)
        "useTabs": false,
        "licenseHeader": "/* Copyright $YEAR Acme */"   // or { "file": "LICENSE_HEADER" }
      },
      "imports": {
        "removeUnused": true,                      // unused, redundant and duplicate imports
        "sort": true,                              // alphabetical, in groups
        "groups": ["*", ["java.*", "javax.*"], "static *"],
        "wildcardThreshold": 0                     // N > 0: N imports from one package become a wildcard
      },
      "linter": {
        "enabled": true,
        "recommended": true,
        "rules": {
          "correctness": { "UnusedPrivateMember": "error" },
          "style": { "MissingOverrideAnnotation": "off" },
          "custom": { "MyRule": { "level": "warn", "options": { "max": 3 } } }
        }
      },
      "overrides": [
        { "include": ["test/**"], "linter": { "rules": { "correctness": { "UnusedLocalVariable": "off" } } } }
      ]
    }
  }
}
```

Each rule is `off`, `warn` or `error`, or `{ "level": …, "options": { … } }`. Rules are grouped by category,
and naming an unknown rule or the wrong category is an error. `wildcardThreshold` is off by default because
merging imports into a wildcard can make names ambiguous.

## Rules

| Rule | Category | Kind | |
|---|---|---|---|
| `JavaFormat` | format | formatter | Layout (forked from Palantir Java Format) |
| `EmptyBodies` | format | formatter | IntelliJ: empty bodies on two lines |
| `MemberBlankLines` | format | formatter | IntelliJ: a blank line around methods, classes and initializers |
| `KeepBlankLines` | format | formatter | IntelliJ: blank lines kept, up to `maxBlankLines` |
| `ImportOrder` | format | formatter | Import groups and order |
| `LicenseHeader` | format | formatter | Header from `formatter.licenseHeader` |
| `UnusedImport` | style | formatter | Unused, redundant and duplicate imports |
| `UnnecessaryModifier` | style | formatter | Modifiers the language implies, such as `public` on interface methods |
| `MissingOverrideAnnotation` | style | lint, needs types | Overriding methods without `@Override` |
| `StringEquality` | correctness | lint, needs types | Strings compared with `==` |
| `UnusedLocalVariable` | correctness | lint | Unused locals and private-method parameters |
| `UnusedPrivateMember` | correctness | lint | Unused private fields and methods |
| `PointlessBooleanExpression` | complexity | lint | `x == true`, `x && true` … |
| `EmptyCatchBlock` | suspicious | lint | Catch blocks that hide the exception |

Details are in [docs/rules.md](docs/rules.md). Rule names match IntelliJ's inspection names, so existing
`@SuppressWarnings` and `//noinspection` suppressions carry over.

**Type-aware rules** use javac's attribution against the project's classpath, from `JPM_CLASSPATH` or
`jpm info --json --classpath`. Outside a jpm project, or with dependencies not installed, they are skipped
with a warning; formatting and the other rules still run.

## Suppressions

```java
// jpm-ignore lint/correctness/UnusedLocalVariable: kept for the debugger
int probe = compute();
```

- `// jpm-ignore lint/<category>/<rule>: <reason>` covers the next line (and the whole statement or
  declaration that starts there, so reformatting cannot move code out of it). `jpm-ignore-all` covers the
  file, `jpm-ignore-start` … `jpm-ignore-end` a range. The target can also be `lint/<category>` or `lint`.
  A reason is required.
- `@SuppressWarnings("RuleName")` on a class, method, field or variable, and `//noinspection RuleName`
  before a statement, as in IntelliJ. Aliases work too: `@SuppressWarnings("unused")`.
- `// jpm-ignore format` keeps the next element as written. `// @formatter:off` and `// @formatter:on`,
  as used by IntelliJ and Eclipse, are honoured too.
- Unused `jpm-ignore` comments are reported (`suppressions/UnusedSuppression`), so they do not pile up.
  `@SuppressWarnings` and `//noinspection` are not, since javac and IntelliJ read them too.

## Custom rules

Write rules in Java, one file per rule, in a `rules/` folder at the project root. jpm-format compiles them
into `.cache/format/rules/` and runs them with the built-in ones.

```java
// rules/NoSystemOut.java
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.api.*;

public class NoSystemOut implements Lint {
    public String id() { return "NoSystemOut"; }

    public void check(Context ctx) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMemberSelect(MemberSelectTree t, Void v) {
                if (t.toString().equals("System.out")) ctx.report(t, "Use a logger");
                return super.visitMemberSelect(t, v);
            }
        }.scan(ctx.tree(), null);
    }
}
```

The API (`dev.jpm.format.api`) is two interfaces:

- **`Lint`** has one method, `check`. It reports problems and never changes code.
- **`Formatter`** extends `Lint` and adds `format`, which returns new source. Its `check` comes built in:
  it passes if formatting returns the input unchanged, and otherwise reports where the output differs. So
  every formatter is also a linter for `--check`. Anything applied automatically is a formatter.

Rules see javac's syntax tree (`ctx.tree()`), the same one the formatter uses, and type information
(`ctx.types()`) when the classpath is known. Only an `id` is required; `category` (default `custom`),
`severity` (`warn` for lints, `error` for formatters), `docs`, `aliases` and `typeAware` are optional.

**Order.** Formatters run in a fixed order, each on the previous one's output. A plain rule file is added
at the end; to place a formatter, make the file a `RuleSet`:

```java
public class MyRules implements RuleSet {
    public void register(Rules rules) {
        rules.addBefore("JavaFormat", new MyFormatter()); // also add(rule) and addAfter(id, rule)
    }
}
```

Naming a rule that does not exist is an error. Formatting must be idempotent; `--debug` checks it.

**Sharing rules.** A rule set can come in as a normal jpm dependency, for example from a Git repo, so one
set is shared across projects like ESLint plugins: list the `RuleSet` class in
`src/assets/META-INF/services/dev.jpm.format.api.RuleSet`.

**Where rules run.** Custom rules are loaded into the JVM jpm-format runs on. The rule API does not depend
on where rules run: when jpm-format is a native binary (see [Status](#status)), a command in a project with
custom rules is handed to a JVM, so projects without them never pay that cost.

### Testing rules

`jpm format test` runs every `rules/test/**/Name.java` and compares the result with snapshots next to it:
`Name.expected.java` (what `jpm format` makes of it) and `Name.findings` (what `--check` reports, one
`line:col category/Rule message` per line). Missing snapshots are written on the first run; `--update`
rewrites them. Commit them, and formatting cannot change by accident. From JUnit, use `RuleTester`:

```java
RuleTester t = RuleTester.of(new NoSystemOut());
t.assertFindings("class A { void f() { System.out.println(); } }", "1:22 custom/NoSystemOut Use a logger");
t.assertFormats(input, expected); // also checks idempotence
```

## Editor support

`jpm format lsp` is a language server for VS Code, Zed, Neovim and others: diagnostics as you type,
document and range formatting, quick fixes for each finding, and "Format and apply safe fixes".

## CI

`jpm format --check` in CI. `--reporter github` gives pull-request annotations and is picked automatically
in GitHub Actions; `--reporter sarif` feeds code scanning; `--reporter json` is for scripts. Results are
cached in `.cache/format/`, keyed by file content, config, tool version and, for type-aware rules, the
classpath, so unchanged files are skipped; restore that folder between CI runs to keep the speed-up. With
[setup-jpm](https://github.com/java-package-manager/setup-jpm) 1.1 or newer that is one input:

```yaml
      - uses: java-package-manager/setup-jpm@v1
        with:
          format-cache: true
      - run: jpm add -g github:java-package-manager/jpm-format
      - run: jpm format --check
```

## Spotless compatibility

The common Spotless steps are built in: import order (`ImportOrder`), removing unused imports
(`UnusedImport`) and license headers (`LicenseHeader`, with `$YEAR`).

## How it works

- One parser for everything: javac's, as Palantir uses, with Palantir's token stream for comments and
  whitespace. It parses every Java version exactly as the compiler does, and the same tree serves the
  formatter, the linter and type attribution.
- The layout engine is a fork of Palantir Java Format 2.103.0 in `src/dev/jpm/format/palantir/`, so its rules can
  change; [third_party/palantir-java-format/UPSTREAM.md](third_party/palantir-java-format/UPSTREAM.md) says what was
  copied and what changed since. Upstream's 226 formatter test cases run as `PalantirTestdataTest`.
- Built-in rules are compiled into jpm-format. Files are processed in parallel.
- Palantir needs javac's internal packages. jpm starts extensions on a plain JVM, so jpm-format exports them
  itself on JDK 21–23, and on newer JDKs starts a second JVM with `--add-exports` (with class data sharing,
  so later starts are fast).
- Newer JDKs changed javac's internal tree in a way Palantir cannot read yet. When jpm starts jpm-format on
  such a JDK, it moves the run to JDK 21 (`jpm java home 21`, which installs it if needed);
  `JPM_FORMAT_JAVA_HOME` picks the JDK instead.

## Status

This is the first version. Not done yet:

- **Native binary.** jpm starts extensions on a JVM today; a GraalVM build (`jpm build --native`) needs
  testing first, since running javac inside a native image is uncommon.
- **IntelliJ parity.** The IntelliJ rules cover blank lines and empty bodies so far; more of IntelliJ's defaults
  (wrapping of long method chains and argument lists, Javadoc formatting) and its options follow, as rules in
  `src/dev/jpm/format/intellij/`. More inspections follow too, with null checks and data flow last.
- **Elsewhere:** `jpm import` translating Spotless config (in jpm), and the IntelliJ plugin running
  `jpm format` on save (in jpm-intellij).

## Developing

Building jpm-format needs jpm 0.7.1 or newer: the layout engine is built on javac's internal syntax tree, which
`"javacArgs"` opens with `--add-exports`, and older jpm versions always compile with `--release`, which javac
refuses together with that. Installing jpm-format (`jpm add -g`) builds it from source, so this applies there too.

```sh
jpm test
jpm build
jpm start -- --check            # jpm-format on itself, on its runtime classpath
```

`JPM_FORMAT_RELAUNCH=1` forces the second-JVM path, to test it on JDK 21.

## License

MIT, see [LICENSE](LICENSE). The forked layout engine in `src/dev/jpm/format/palantir/` is Apache 2.0, from Palantir
Java Format and google-java-format: see [third_party/palantir-java-format](third_party/palantir-java-format).
