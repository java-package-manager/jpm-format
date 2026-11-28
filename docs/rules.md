# Rules

Every rule's id is the name used in config (`linter.rules.<category>.<id>`), in
`// jpm-ignore lint/<category>/<id>: <reason>`, in `@SuppressWarnings("<id>")` and in `//noinspection <id>`.
Formatters are applied by `jpm format`; their findings in `--check` are differences from the formatted
text. Lint rules only report.

## format

### JavaFormat

The layout of the code: indentation, line breaks, spacing. In the default `java.style: "intellij"` it is
jpm-format's own layout engine (ADR 0006), which keeps the input's line breaks, as IntelliJ does ("Keep when
reformatting: line breaks"): no line is joined, except that `{` moves up to the line it opens from, `else`,
`catch`, `finally` and the `while` of a `do` move up after a `}`, and a switch rule's body moves up after its `->`
when it fits; statements get a line each, block contents and the `}` lines of their own, and the annotations of
classes, methods, fields and modules go on their own lines, and the modifiers, keyword or type and name of a
class, method or variable join one line (IntelliJ keeps a break between them, which formatted code never has); except a type-use nullness annotation IntelliJ knows
(JSpecify's `@Nullable` and `@NonNull`, JetBrains' `@Nullable` and `@NotNull`) written right before the type on its
line (`@Nullable String name()`). The body of an `if`, a loop or a switch rule that would end
past `java.lineWidth` moves to the next line, and so does a record component, as IntelliJ's "wrap if long" does: the
first one whose line would end past `java.lineWidth` before its comma (for the last one, before the end of the line,
a comment at its end included), lined up with the first component, or one continuation indent in if that is the one
that moves. Method parameters and arguments are not wrapped, as in IntelliJ's default code style. A `//` comment
after the annotations of a method or constructor whose modifiers are only annotations goes on a line of its own,
and the comments between them and the type are one indent further in unless a Javadoc comment comes first; after
any other annotation of a method, the comment goes on a line of its own only when its line would end past
`java.lineWidth`. On a field, a class or anything else it stays. A wrapped line is indented one continuation indent from the
nearest enclosing statement, argument, chain or operand that starts a line, not from every expression it is in
(`foo(bar(a,` then `b))` puts `b` one continuation in), a block from the line it starts on, an anonymous class's
body from the line its `new` is on, and a closing `)` on
its own line under the line with its `(`. Method
parameters, the parts of a `for` header and try resources line up ("Align when multiline"), array initializers are
one continuation in and written `new int[]{1}`, and an `if` on its own line after an `else` and a comment is that
`else`'s statement. Modifiers keep the order they are written in, and the annotations and modifiers of a local
variable or parameter join the line of its type. A wrapped `throws` list is one continuation in from the method.
In a `case` group with a `:`, a block that is the group's first statement joins the label's line (`case 1: {`, not
after a comment), its contents one indent in from the label; a block on a line of its own and the statements from
a block on are at the label's indent, as IntelliJ puts them.
Comments stay as written: line comments are not wrapped and keep the text after their `//`, comments at the first
column stay there ("Line comment at first column"), a comment after the statements of a `case` stays indented with
them, and the other lines of a block comment move as far as its first.

`java.lineWidth` (20 to 1000) is where statement bodies move to the next line. Configure with
`java.indentWidth` (1 to 16),
`java.continuationIndentWidth` (1 to 32; twice `indentWidth` by default, as IntelliJ's 4 and 8) and
`java.useTabs`. In the IntelliJ style a text block moves as a whole to one continuation indent in from the
line it starts on, as in IntelliJ, which keeps its value; tabs never go into it. Turn the whole formatter off with
`java.enabled: false`.

`java.style` can also name another formatter: `"palantir[@<version>]"` (Palantir Java Format, 2.103.0 without a
version: the release jpm-format's own fork of it came from, before ADR 0006 removed the fork),
`"google-java-format[@<version>]"` (its latest release without a version), in any version syntax jpm reads, or
`"eclipse[@<version>]"`: Eclipse's JDT formatter, through Spotless's Eclipse step (Spotless 4.12.0, the Eclipse
version Spotless picks without one), with the profile exported from Eclipse in `java.eclipseConfig`. jpm
downloads it (`dependencies/resolve`, so it needs jpm 0.15.0 or newer), and it runs in a class loader of its own
with its own defaults: `lineWidth`, the indentation settings, `useTabs` and the IntelliJ rules do not apply. The
import rules, `LicenseHeader` and the linter still do.

It can also name a formatter that is not on a Maven repository: the URL of a jar (`https://`, or `file:`), with
`#sha256=<hex>` after it to pin what is downloaded (kept in `.cache/format/formatters/`), or a Git repository
that jpm builds, `"github:author/formatter[@<version>|#<branch>]"` (also `gitlab:` and `codeberg:`; its newest
tag without either; needs jpm 0.15.0 or newer, with the project's `dir`). The jar is either a build of Palantir
Java Format or google-java-format (their `Formatter` class, with their defaults), or a jpm-format rule set: a
`RuleSet` listed in `META-INF/services/dev.jpm.format.api.RuleSet` whose `Formatter` rules then run in place of
the layout engine, in the order they register, each on the previous one's output.

### EmptyBodies

IntelliJ's "Keep when reformatting: simple methods / classes / blocks in one line", off by default: an empty body is
written `{` and `}` on two lines, the closing brace under the first line of the declaration or statement (the
line of the brace for `else`, `catch`, `finally` and `do`). Applies to methods and constructors, named
classes, initializer blocks and the blocks of `if`, `else`, loops, `try`, `catch`, `finally` and `synchronized`.
Lambdas, anonymous classes, `switch` cases and array initializers keep `{}`. Runs after `JavaFormat`; off with
`java.style: "palantir"`.

### MemberBlankLines

IntelliJ's "Minimum blank lines": one blank line around methods, nested classes and initializer blocks; none
required between fields, after the class header or before its end. The blank line goes before the member's
comments (its Javadoc, a line comment); when it has a Javadoc, right before
the Javadoc, as IntelliJ does not count other comments before it as the member's, also when the member is the
class's first. Runs after `JavaFormat`; off with `java.style: "palantir"`.

### KeepBlankLines

IntelliJ's "Keep maximum blank lines" (in declarations, in code and before `}`): blank lines the code already had
stay, up to `java.maxBlankLines` (2 by default, 1 to 10). Runs after `JavaFormat`; off with
`java.style: "palantir"`.

### JavadocLayout

IntelliJ's default Javadoc formatting:

- "Align parameter descriptions" and "Align thrown exception descriptions": the descriptions of the `@param`
  tags start in one column, after the longest name, and so do those of the `@throws` and `@exception` tags; their
  continuation lines start under the description
- "Blank lines: after description": one blank line between the description and the first block tag, and none
  between block tags ("after parameter descriptions" and "after return tag", off); one space after `@return`
- "Do not wrap one line comments", off: `/** Text. */` becomes three lines
- "Indent continuation lines", off: continuation lines of the other block tags start under the tag
- lines of the description and of block tags lose their indentation, as in IntelliJ: not inside `<pre>` or
  `{@snippet}`, and from a line that starts a `<table>`, `<ol>`, `<ul>`, `<div>` or `<dl>` on, the lines keep
  their indentation less the smallest one
- "Generate `<p>` on empty lines": an empty line of the description that more text follows gets a `<p>`
- a description written on the line after its `@param` or `@throws` name moves up to the tag
- the parameter types of `{@link}`, `{@linkplain}` and `@see` references are separated by a comma and a space
- block tags in IntelliJ's order: `@param`, `@return`, `@throws` (`@exception` becomes `@throws`), the other tags,
  `@see`, `@since`, `@deprecated`; tags of one kind keep their order and their continuation lines (not when a
  `<pre>` is among the tags)

Only the Javadoc IntelliJ formats is formatted: that of the package, the module, top-level types and the fields,
methods and classes of the classes among them, not that of local or anonymous classes' members, initializers or
local variables. A line whose text starts with `@` is a block tag, also after more than one space; `<pre>` with
attributes (`<pre class="code">`) counts as `<pre>`.

Text is not re-wrapped. Comments without a `*` at the start of every line are left as they are; a `<pre>` in a
comment does not stop the rest of it from being formatted. Runs after `KeepBlankLines`; off with `java.style: "palantir"`.

### LineCommentSpacing

IntelliJ's "Add a space at line comment start", off by default: `//TODO` and `//$NON-NLS-1$` keep the text right
after the slashes. The space before a comment that ends a line of code stays as
written too (`case 'a':// fall through`, `int x;  // why`). Runs after `JavadocLayout`; off with
`java.style: "palantir"`.

### ImportBlankLines

The blank lines between imports, as IntelliJ's formatter sets them from its import layout (`imports.groups`):
none between imports of one group, one between imports of different groups, in the order they are written
(`ImportOrder` sorts them). Imports with a comment between them are left alone. Runs after `LineCommentSpacing`;
off with `java.style: "palantir"`.

### ImportOrder

Sorts imports into `imports.groups` (default: all other imports, then `javax.*` and `java.*`, then static
imports, as IntelliJ's default import layout), a blank line between groups; within a group, the imports of each
pattern in the pattern's order, each alphabetical. A pattern is `*`, a prefix like `java.*`,
or an exact name; prefix with `static ` for static imports; the most specific pattern wins. Duplicates are
dropped. With `imports.wildcardThreshold` > 0, that many imports from one package become `package.*`.
Imports with comments between them are left alone. Off with `imports.sort: false`.

### LicenseHeader

Puts `java.licenseHeader` (a comment, or `{ "file": "…" }`) at the top of every file, replacing the
comments before the `package` line, like Spotless. `$YEAR` is the current year, but a year or range already in
a matching header is kept. In a file without a package, other leading comments such as a class's Javadoc are
kept after the header. Only runs when a header is configured.

## style

### UnusedImport

Removes unused imports, redundant ones (`java.lang.*` and the file's own package) and duplicates. Names used
only in Javadoc (`{@link}`, `{@linkplain}`, `{@value}`, `@see`, `@throws`, including the parameter types of
`{@link #m(Map)}`) or in Markdown doc comments (`/// [File]`) count as used. Wildcard imports are removed only
when redundant or duplicated. Off with `imports.removeUnused: false`.

### UnnecessaryModifier

Removes modifiers the language implies: `public` and `abstract` on interface methods, `public static final` on
interface fields, `public static` on types in interfaces, `static` on nested interfaces, enums and records,
`final` on records, `private` on enum constructors. Removing them never changes behaviour.

### MissingOverrideAnnotation

*Needs types.* A method that overrides or implements a supertype's method but has no `@Override`. The fix
adds it (offered, not applied).

### NonJavadocComment

Off by default. A `//` or `/* */` comment, for projects that keep decisions in documents and explanations in
Javadoc. Javadoc, suppression comments (`jpm-ignore`, `noinspection`) and comment markers inside strings,
character literals and text blocks are left alone. The fix removes the comment, a comment on a line of its own
with its line and a trailing one with the space before it; it is offered, not applied. Turn it on with
`"linter": { "rules": { "style": { "NonJavadocComment": "error" } } }`, and leave out vendored code with
`ignore` or `overrides`.

## correctness

### StringEquality

*Needs types.* Two strings compared with `==` or `!=`, which compares identity, not content. Comparisons
with `null` are fine. The fix, `a.equals(b)`, is offered, not applied: it throws if `a` is null.

### UnusedLocalVariable

A local variable never used after its declaration, or a parameter of a private method that its body never
uses. Names starting with `ignored` or `unused`, and `_`, are skipped. Reported only, since removing the
variable could drop side effects of its initializer. Also suppressed by `unused`.

### UnusedPrivateMember

A private field or method that nothing in the file refers to. Annotated members (frameworks may use them),
serialization members (`serialVersionUID`, `readObject` …), record components and files that import Lombok
are skipped. Also suppressed by `unused`.

## complexity

### PointlessBooleanExpression

A comparison with a boolean literal that can be simpler: `x == true` is `x`, `x != true` and `x == false`
are `!x`, `x && true` and `x || false` are `x`. The fix is offered, not applied: with a `Boolean`, the
simpler form has a different static type.

## suspicious

### EmptyCatchBlock

A catch block with nothing in it hides the exception. A comment in the block, or a parameter named
`ignored`, `ignore`, `expected` or `_`, marks it as deliberate. Reported only.

## suppressions

### UnusedSuppression

A `jpm-ignore` comment that suppresses nothing. Only reported when the rules it names ran.

### InvalidSuppression

A `jpm-ignore` comment without a reason, with an unknown target, or a `jpm-ignore-start` without its
`jpm-ignore-end`. It suppresses nothing.

## Always on

- `syntax/ParseError`: the file does not parse; nothing else runs on it, and it is left unchanged.
- `internal/NotIdempotent` (`--debug`): formatting a formatter's output again changes it.
- `internal/RuleCrash`: a rule threw, or a formatter produced code that does not parse (its change is dropped).
