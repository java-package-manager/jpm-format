# jpm-format

`jpm format` for [jpm](https://github.com/java-package-manager/jpm): one tool that formats and lints Java,
with an opinionated IntelliJ-style default and your own rules.

```sh
jpm add -g github:java-package-manager/jpm-format   # needs jpm 0.12.0 or newer
jpm format            # format every file and apply every safe fix
jpm format --check    # change nothing; report problems and exit 1 if there are any (for CI)
```

It is a jpm extension: jpm runs `jpm format` as `jpm-format`. It works like Spotless: `jpm format` is
`spotlessApply`, `jpm format --check` is `spotlessCheck`.

## Commands

|            Command            |                                                                                                                        |
|-------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `jpm format [paths…]`         | Formats every file and applies every safe fix, such as removing unused imports. Lint findings that remain are printed. |
| `jpm format --check [paths…]` | Changes nothing. Reports formatting differences and lint findings together and exits 1 if there are any.               |
| `jpm format --stdin <path>`   | Formats standard input as if it were `<path>` and prints the result (for editor integrations).                         |
| `jpm format lsp`              | Runs the language server over stdio (also `jpm-format lsp`).                                                           |
| `jpm format test [--update]`  | Runs the rule tests in `rules/test/` (see [Testing rules](#testing-rules)).                                            |
| `jpm format rules`            | Lists every rule, its category and level.                                                                              |

Options: `--reporter pretty|json|sarif|github`, `--debug` (runs every formatter twice and reports any whose
output changes), `--no-cache`, `--threads <n>`.

Exit codes: 0 when clean, 1 for findings (`--check`) or files that do not parse, 2 for a bad config or option.

Safe fixes are only ones that cannot change behaviour, and they are applied by formatters. Riskier findings
are reported, with their fix offered in the editor and in the JSON and SARIF reports, but never applied
automatically.

## Formatting style

IntelliJ IDEA's default Java code style, from jpm-format's own layout engine (`src/dev/jpm/format/intellij/`,
[ADR 0006](docs/adr/0006-intellij-style-without-palantir.md)). It keeps your line breaks as IntelliJ does and
changes only whitespace: a line is joined or broken only where IntelliJ always does it (one statement per line,
block contents on their own lines, annotations of declarations on their own lines (not a known type-use
`@Nullable` before the type), `{` at the end of the line,
`} else`, `} catch`, `} finally`), and a statement body or a record component that would end past `lineWidth`
moves to the next line.
Blocks are 4 in from the line they start on; a line that continues something is one continuation indent (8) in
from the nearest enclosing statement, argument, chain or operand that starts a line; method parameters, `for`
headers, try resources and record components line up as in IntelliJ. The IntelliJ rules then handle the rest:

- blank lines the code has are kept, up to 2 (also before a closing brace);
- methods, nested classes and initializers get a blank line around them, also when they start with a comment;
- empty methods, classes and blocks are written `{` and `}` on two lines; lambdas and anonymous classes keep `{}`;
- Javadoc gets aligned `@param` descriptions, a blank line before the tags and `<p>` on empty lines;
- comments stay as written: line comments are not wrapped, keep their spacing and stay at the first column.

`"java": {"style": "palantir"}` is Palantir Java Format's output as it is (2.103.0, from its own jar): it
chooses the line breaks itself, wrapping at 120. Imports follow
IntelliJ's layout: all other imports, then `java.*` and `javax.*`, then static imports, each group sorted
alphabetically and separated by a blank line. Javadoc is not reflowed.

## Configuration

Opinionated defaults; nearly everything can be overridden. The shape follows Biome, with Prettier's option
names. It lives in `project.jsonc` under `extensions.format`, or in a separate `jpm-format.jsonc` at the
project root (not both). In a workspace, a module's own `project.jsonc` applies to the files under it.

Files git ignores are not formatted or checked: jpm-format reads the `.gitignore` files from the repository's root
down (and `.git/info/exclude`) by git's rules, so the `build/` folders of a multi-module build are skipped while a
`!src/main/**/build/` keeps a package named `build`. git does not need to be installed.

```jsonc
{
  "extensions": {
    "format": {
      "extends": ["config/format.jsonc"],          // shared configs, merged in order, then this one
      "ignore": ["src/generated/**"],              // besides what .gitignore ignores; .cache, dist, build, target, .git … always are
      "java": {
        "enabled": true,
        "style": "intellij",                       // or "palantir", "palantir@<version>", "google-java-format[@<version>]",
                                                   // "eclipse[@<version>]" (with "eclipseConfig": "<profile>.xml")
                                                   // a jar's URL ("…/f.jar#sha256=…") or "github:author/formatter[@<version>|#<branch>]"
        "lineWidth": 120,                          // 20 to 1000; long statement bodies move to the next line past it
        "indentWidth": 4,                          // 1 to 16
        "continuationIndentWidth": 8,              // wrapped lines; twice indentWidth by default, as in IntelliJ
        "maxBlankLines": 2,                        // blank lines kept in a row, 1 to 10
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

|             Rule             |  Category   |         Kind         |                                                                                                          |
|------------------------------|-------------|----------------------|----------------------------------------------------------------------------------------------------------|
| `JavaFormat`                 | format      | formatter            | Layout (IntelliJ's default Java code style)                                                              |
| `EmptyBodies`                | format      | formatter            | IntelliJ: empty bodies on two lines                                                                      |
| `MemberBlankLines`           | format      | formatter            | IntelliJ: a blank line around methods, classes and initializers                                          |
| `KeepBlankLines`             | format      | formatter            | IntelliJ: blank lines kept, up to `maxBlankLines`                                                        |
| `JavadocLayout`              | format      | formatter            | IntelliJ: aligned `@param` and `@throws` descriptions, a blank line before the tags, `<p>`               |
| `LineCommentSpacing`         | format      | formatter            | IntelliJ: `//comment` keeps its text right after the slashes                                             |
| `ImportOrder`                | format      | formatter            | Import groups and order                                                                                  |
| `LicenseHeader`              | format      | formatter            | Header from `java.licenseHeader`                                                                         |
| `UnusedImport`               | style       | formatter            | Unused, redundant and duplicate imports                                                                  |
| `UnnecessaryModifier`        | style       | formatter            | Modifiers the language implies, such as `public` on interface methods                                    |
| `MissingOverrideAnnotation`  | style       | lint, needs types    | Overriding methods without `@Override`                                                                   |
| `NonJavadocComment`          | style       | lint, off by default | `//` and `/* */` comments                                                                                |
| `StringEquality`             | correctness | lint, needs types    | Strings compared with `==`                                                                               |
| `UnusedLocalVariable`        | correctness | lint                 | Unused locals and private-method parameters                                                              |
| `UnusedPrivateMember`        | correctness | lint                 | Unused private fields and methods                                                                        |
| `PointlessBooleanExpression` | complexity  | lint                 | `x == true`, `x && true` …                                                                               |
| `EmptyCatchBlock`            | suspicious  | lint                 | Catch blocks that hide the exception                                                                     |
| 149 IntelliJ inspections     | all         | lint                 | IntelliJ's default Java inspections, by their IntelliJ names: [docs/inspections.md](docs/inspections.md) |

Details are in [docs/rules.md](docs/rules.md). Rule names match IntelliJ's inspection names, so existing
`@SuppressWarnings` and `//noinspection` suppressions carry over.

**Type-aware rules** use javac's attribution against the project's classpath, from `JPM_CLASSPATH` or
jpm over its protocol (`jpm api`, jpm 0.12.0 or newer, with jpm's client `dev.jpm:jpm-api`). Outside a jpm
project, with an older jpm, or when jpm cannot load the project, they are skipped with a warning that says
why; formatting and the other rules still run.

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
on where rules run: when jpm-format is a native binary (see [ROADMAP.md](ROADMAP.md)), a command in a project with
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
document and range formatting, quick fixes for each finding, and "Format and apply safe fixes". In
`project.jsonc` it shows jpm's own warnings and errors (what `jpm check` reports for the manifest, its
dependencies, license and paths) at the line they are about, when the file is opened and saved, as
jpm-intellij does.

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

## Other file types

`.properties`, JSON, XML, YAML and TOML files are formatted out of the box, by jpm-format's own formatters (nothing is
downloaded), as IntelliJ IDEA's default code style does, with a few differences that keep files the way they were
meant:

|                          Files                          |                                                       Formatting                                                        |                                                                                                            Unlike IntelliJ                                                                                                            |
|---------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `*.properties`                                          | `key=value` without spaces around `=` or `:`, no leading whitespace; values and continuation lines as written           | blank lines are kept (up to 2)                                                                                                                                                                                                        |
| `*.json`, `*.jsonc`                                     | indent 2; `"a": 1`, `[1, 2]`; a line one indent in from the line its object or array opens on; comments stay            | line breaks are kept: IntelliJ puts every value on a line of its own                                                                                                                                                                  |
| `*.yml`, `*.yaml`                                       | each level 2 in, a sequence under a key one level in, one space after `key:` and `-`; a block scalar moves with its key | flow collections (`[a, b]`) stay as written, where IntelliJ writes `[ a, b ]`; a file whose structure is not plain enough to be sure of (tabs, multi-line flow collections or quoted values, indentation indicators) is left as it is |
| `*.toml` (Gradle's `libs.versions.toml`)                | `key = value`, `{ a = 1 }`, the lines of a multi-line array 4 in; strings and comments as written                       |                                                                                                                                                                                                                                       |
| `*.xml`, `*.xsd`, `*.xsl`, `*.xslt`, `*.wsdl`, `*.fxml` | indent 4; one child element per line; attributes one space apart, those on lines of their own lined up with the first   | text, CDATA and `xml:space="preserve"` content are never changed, and long lines are not wrapped                                                                                                                                      |

A file that does not parse is left as it is.

Markdown (`*.md`, `*.markdown`) and Cucumber's `*.feature` files are formatted out of the box too, by Spotless's
`flexmark` and `gherkinUtils` steps, which jpm downloads the first time. Flexmark runs with jpm-format's defaults:
YAML front matter stays front matter, code blocks keep their indentation (formatting never changes code), tables are
aligned, and a file ends with one line break; its options (`"files"`, below) go on top of these. A `"files"` entry
that includes a file replaces its default, and `"steps": []` turns it off.

### Settings per file type

Each file type has its own defaults. `"default"` sets what applies to every type that does not set it itself, and
a section named after a type sets that type's settings, and its own options:

```jsonc
"format": {
  "default": { "indentWidth": 4 },                       // every type without an indentWidth of its own
  "java": { "indentWidth": 2, "useTabs": true },         // Java files only
  "json": { "indentWidth": 2, "style": "compact" },      // "keep" (default), "compact" or "pretty"
  "properties": { "wrap": true, "encoding": "ISO-8859-1" }
}
```

|   Section    |                                                                                                                            Settings (default)                                                                                                                             |
|--------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `default`    | `lineWidth`, `indentWidth`, `continuationIndentWidth`, `maxBlankLines`, `useTabs`: each type takes those it has                                                                                                                                                           |
| `java`       | `enabled`, `style`, `eclipseConfig`, `lineWidth`, `indentWidth` (4), `continuationIndentWidth`, `maxBlankLines`, `useTabs`, `licenseHeader` (see [Configuration](#configuration))                                                                                         |
| `json`       | `style`: `"keep"` keeps line breaks; `"compact"` puts an object or array that fits in `lineWidth` on one line (`{ "a": 1 }`, `[1, 2]`) and the rest one value per line; `"pretty"` puts every value on a line of its own. `indentWidth` (2), `maxBlankLines`, `lineWidth` |
| `xml`        | `indentWidth` (4), `maxBlankLines`                                                                                                                                                                                                                                        |
| `yaml`       | `indentWidth` (2), `maxBlankLines`                                                                                                                                                                                                                                        |
| `toml`       | `indentWidth` (4), `maxBlankLines`                                                                                                                                                                                                                                        |
| `properties` | `wrap`: values longer than `lineWidth` go on over `\` continuation lines, `indentWidth` (4) in; `encoding`: `"ISO-8859-1"` writes characters outside ASCII as Unicode escapes, `"UTF-8"` writes those escapes as the characters; `maxBlankLines`, `lineWidth`             |

A `"files"` entry (below) that includes a file replaces these defaults, and one with `"steps": []` turns them off;
the built-in steps are `properties`, `json`, `xml`, `yaml` and `toml`, which take the options above (`indent` for `indentWidth`):

```jsonc
"files": [
  { "include": ["src/main/resources/legacy/**"], "steps": [] },
  { "include": ["**/*.json"], "steps": [{ "step": "json", "indent": 4 }] }
]
```

## Spotless compatibility

The common Spotless steps are built in: import order (`ImportOrder`), removing unused imports
(`UnusedImport`) and license headers (`LicenseHeader`, with `$YEAR`).

Files other than Java get Spotless's own steps, listed in `"files"` (in the root config only): each entry
names the files it applies to and the steps that format them, in order. jpm provides Spotless's jars and the
formatters the steps download (needs jpm 0.15.0 or newer), and `jpm format --check` reports a file the steps
would change.

```jsonc
"files": [
  { "include": ["**/*.kt", "**/*.kts"], "steps": ["ktfmt", "trimTrailingWhitespace", "endWithNewline"] },
  { "include": ["**/*.scala"], "steps": [{ "step": "scalafmt", "version": "3.8.3", "config": ".scalafmt.conf" }] },
  { "include": ["**/*.json"], "steps": [{ "step": "jsonSimple", "indent": 2 }] },
  { "include": ["**/*.md"], "steps": ["trimTrailingWhitespace", "endWithNewline"] }
]
```

|                    Step                    |                                 Formats                                 |                                                                                                             Options                                                                                                              |
|--------------------------------------------|-------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ktfmt[@<version>]`                        | Kotlin, with ktfmt                                                      |                                                                                                                                                                                                                                  |
| `ktlint[@<version>]`                       | Kotlin, with ktlint                                                     |                                                                                                                                                                                                                                  |
| `scalafmt[@<version>]`                     | Scala, with scalafmt                                                    | `config`: a `.scalafmt.conf`                                                                                                                                                                                                     |
| `jacksonJson`, `jsonSimple`                | JSON                                                                    | `jsonSimple`: `indent` (default 4)                                                                                                                                                                                               |
| `jacksonYaml`                              | YAML                                                                    |                                                                                                                                                                                                                                  |
| `dbeaverSql`                               | SQL, with DBeaver's formatter                                           | `config`: a DBeaver properties file                                                                                                                                                                                              |
| `flexmark[@<version>]`                     | Markdown, with Flexmark                                                 | `emulationProfile` (`"COMMONMARK"`, ...), `extensions` (`["Tables", "YamlFrontMatter"]`), `pegdownExtensions`, `formatterOptions`: Flexmark `Formatter` options with integer, boolean or string values (`{"RIGHT_MARGIN": 100}`) |
| `gherkinUtils[@<version>]`                 | Cucumber `.feature` files                                               | `indent` (default 2)                                                                                                                                                                                                             |
| `sortPom[@<version>]`                      | `pom.xml`, with SortPom                                                 | SortPom's options by their names in Spotless (`nrOfIndentSpace`, `expandEmptyElements`, `predefinedSortOrder`, `sortOrderFile`, `sortDependencies`, `sortPlugins`, `sortProperties`, ...)                                        |
| `trimTrailingWhitespace`, `endWithNewline` | any text                                                                |                                                                                                                                                                                                                                  |
| `indent`                                   | any text                                                                | `type`: `"space"` (default) or `"tab"`, `width` (default 4)                                                                                                                                                                      |
| `greclipse[@<version>]`                    | Groovy and Gradle, with Groovy-Eclipse                                  | `config`: an Eclipse formatter preferences file                                                                                                                                                                                  |
| `removeSemicolons`                         | Groovy                                                                  |                                                                                                                                                                                                                                  |
| `properties`, `json`, `xml`                | jpm-format's own, on by default ([Other file types](#other-file-types)) | `maxBlankLines`; `json`, `xml`: `indent`                                                                                                                                                                                         |

A step takes its formatter's version after `@` (or as `"version"`), else Spotless's default. Globs starting with
`**/` match at the project root too. Java files are always formatted by jpm-format itself.

## How it works

- One parser for everything: javac's. It parses every Java version exactly as the compiler does, and the same
  tree serves the formatter, the linter and type attribution.
- The IntelliJ style's layout engine (`Layout`, with `Tokens` and `Syntax`) rewrites only the whitespace between
  javac's tokens. How close it is to IntelliJ is measured on real projects (`checks/Parity.java`).
- The other styles (`"palantir"`, `"google-java-format"`, `"eclipse"`, a jar or a Git repository) run the formatter
  itself, which jpm downloads, in a class loader of its own.
- Built-in rules are compiled into jpm-format. Files are processed in parallel.
- The IntelliJ style runs on any JDK with javac, in the JVM jpm starts. Palantir Java Format and google-java-format
  need javac's internal packages, so with another `java.style` jpm-format exports them itself on JDK 21–23, and
  on newer JDKs starts a second JVM with `--add-exports` (with class data sharing, so later starts are fast).
- Newer JDKs changed javac's internal tree in a way Palantir Java Format cannot read yet. With another
  `java.style`, when jpm starts jpm-format on such a JDK, it moves the run to JDK 21 (`jpm java home 21`, which installs it if needed);
  `JPM_FORMAT_JAVA_HOME` picks the JDK instead.

## Status

Before 1.0, nothing is stable. What is still to come is in [ROADMAP.md](ROADMAP.md).

## Developing

Building jpm-format in any project needs jpm 0.14.0 or newer. The layout engine is built on javac's internal syntax tree, which
`"javacArgs"` opens with `--add-exports`, and javac refuses that together with `--release`. jpm 0.14.0 builds an
extension with the JDK its own `"java"` names (21 here), so it compiles without `--release` whatever the project's
`"java"`; older versions build it with the project's JDK, and stop with `"javacArgs" has --add-exports ...` when
that is not 21 (`jpm jvm exec 21 jpm format` works around it). Installing jpm-format (`jpm add -g`) builds it from
source, so this applies there too.

```sh
jpm test
jpm build
jpm start -- --check            # jpm-format on itself, on its runtime classpath
```

`JPM_FORMAT_RELAUNCH=1` forces the second-JVM path, to test it on JDK 21.

## License

MIT, see [LICENSE](LICENSE).
