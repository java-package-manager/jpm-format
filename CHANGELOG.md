# jpm-format

## 0.7.0

### Minor Changes

- New `flexmark` (Markdown) and `gherkinUtils` (Cucumber `.feature`, option `indent`) steps for `"files"`
- `flexmark` takes `emulationProfile`, `extensions`, `pegdownExtensions` and `formatterOptions`; a failing step reports the cause instead of `InvocationTargetException`
- Files that `.gitignore` ignores (read by git's rules, from the repository's root down, and `.git/info/exclude`) are not formatted or checked, so the `build/` folders of the modules of a multi-module build are skipped
- New `greclipse` step for `"files"`: Groovy and Gradle files with Groovy-Eclipse, `config` for Eclipse preferences
- New `sortPom` step for `"files"`: sorts `pom.xml` with SortPom, with SortPom's options by their names in Spotless
- Markdown (`flexmark`) and Cucumber `.feature` files (`gherkinUtils`) are formatted by default; Flexmark keeps YAML front matter and the indentation of code blocks, ends a file with one line break, and runs until its output no longer changes

### Patch Changes

- IntelliJ style: empty lambda and anonymous class bodies go on two lines, as other empty bodies
- Javadoc block tags are put in IntelliJ's order (`@param`, `@return`, `@throws`, other tags, `@see`, `@since`, `@deprecated`), and `@exception` becomes `@throws`
- IntelliJ style: the `?` and `:` lines of a nested conditional expression are no longer aligned with its condition; they are one continuation in, as in IntelliJ
- IntelliJ style: a space after an annotation before type parameters and varargs is kept; Javadoc lists in block tags and a wrapped `{@link}` parameter list are laid out as in IntelliJ, and an empty block tag takes the next line up
- Javadoc: blank lines between the description and the first block tag become one, as in IntelliJ
- IntelliJ style: a known type-use `@Nullable` stays before the type, `module` after an annotation is not indented, `if (b) ((T) x).m()` keeps its space, `String @Nullable []` keeps its space, whitespace-only text block lines keep their whitespace, and lambda bodies in wrapped chains and anonymous class bodies are indented as in IntelliJ
- Javadoc lines lose their indentation as in IntelliJ: not inside `<pre>`, and inside lists and tables they keep it less the smallest one
- IntelliJ style: the blank lines between imports follow the import layout (`ImportBlankLines`), a first method's Javadoc after a comment gets a blank line before it, and blank lines at the end of a file are kept up to `maxBlankLines`
- IntelliJ style: the spaces after a block comment on a line stay as written, unless an operator IntelliJ always spaces follows
- IntelliJ style: no blank line is added between members when one is already between them, among the comments before the next one (not when that one has a Javadoc)
- IntelliJ style: `non-sealed` no longer becomes `non - sealed`, which did not parse and left the file unformatted
- A line starting with `@` inside a Javadoc `<pre>` (such as `@Override` in an example) is no longer taken for a block tag
- IntelliJ style: parameters line up after a comment on the line of their `(`, as in IntelliJ
- Javadoc: only that of declarations IntelliJ formats is formatted (not that of local or anonymous classes' members, initializers or local variables), an indented `@` line is a block tag, `<pre class="...">` counts as `<pre>`, and a line without a space after its `*` gets one
- IntelliJ style: when a call's arguments wrap, the arguments on the line of its `(` are indented as IntelliJ does: their continuation lines and text blocks one continuation further when a later argument starts a line, the calls and lambda bodies they open also when the `)` is on a line of its own
- A line break between the modifiers, keyword or type and name of a class, method or variable is joined (`record` and its name on two lines), and the lines of such a header are no longer continuation lines

## 0.6.0

### Minor Changes

- YAML files are formatted out of the box, as IntelliJ indents them (2 per level, sequences under a key one level in, one space after key: and -); flow collections stay as written, and a file whose structure is not plain enough to be sure of is left alone.
- "formatter" is gone from the config: Java's settings (style, lineWidth, licenseHeader …) go in "java", and those for every file type in "default". A config with "formatter" says so.
- The IntelliJ style now runs on jpm-format's own layout engine instead of the Palantir fork. It is closer to IntelliJ: it moves an if, loop or switch-rule body that would end past lineWidth to the next line, lines up multi-line case labels, indents continuation lines from the nearest enclosing part that starts a line, and moves text blocks to the continuation indent. Expect some reformatting on the first run. Range formatting in the language server formats only the lines it touches.
- TOML files (Gradle's libs.versions.toml) are formatted out of the box, as IntelliJ does: key = value, { a = 1 } inline tables, multi-line arrays indented.
- Other file types out of the box: .properties, JSON/JSONC and XML files are formatted by default, by jpm-format's own formatters in IntelliJ's default style (blank lines in .properties and line breaks in JSON kept; XML text never changed). A "files" entry with "steps": [] turns them off.
- Settings per file type are written "default": { ... } for every type without its own, and "java": { ... }, "json": { ... } (and xml, yaml, toml, properties) for one type; each type keeps its own defaults (JSON and YAML 2, the others 4) unless one of them sets it.
- JSON has styles (keep, compact, pretty); .properties can wrap long values and enforce an encoding (ISO-8859-1 with Unicode escapes, or UTF-8).
- The Palantir Java Format fork is gone (ADR 0006): "palantir" now runs Palantir Java Format 2.103.0 from its own jar, so formatter.lineWidth and the indentation settings no longer apply to it, and jpm-format no longer depends on Guava, Functional Java and Jackson.

### Patch Changes

- The IntelliJ style's own layout engine (ADR 0006), built next to the Palantir fork and measured against IntelliJ: 905 lines differ on the parity corpus, against 980 with the fork.
- Javadoc with <pre> after its tags is formatted like any other (aligned @param descriptions, a blank line before the tags), as IntelliJ does; it was left as it was.
- The default IntelliJ style starts on any JDK, without a second JVM: only Palantir Java Format and google-java-format need javac's internals exported and JDK 21, so only a formatter.style other than "intellij" asks for them.

## 0.5.0

### Minor Changes

- `"files"` runs Spotless's steps on files other than Java, such as ktfmt or ktlint for Kotlin, scalafmt, JSON, YAML, SQL and the generic text steps, with jpm providing the jars; `jpm format --check` reports a file the steps would change
- `formatter.style: "eclipse[@<version>]"` formats with Eclipse's JDT formatter through Spotless's Eclipse step, with the profile in `formatter.eclipseConfig`
- `formatter.style` takes `palantir@<version>` and `google-java-format[@<version>]`: jpm (0.15.0 or newer) downloads that formatter release, and jpm-format runs it in a class loader of its own
- The language server shows jpm's warnings and errors in project.jsonc, from `jpm check` over jpm's protocol, when the file is opened and saved
- `formatter.style` takes a formatter that is not on a Maven repository: the URL of a jar (with `#sha256=` to pin it), or a Git repository that jpm builds (`github:author/formatter[@<version>|#<branch>]`), holding Palantir's or google-java-format's `Formatter` or a jpm-format rule set with formatters

### Patch Changes

- jpm-format runs from its bundle jar on JDK 24 and newer: the relaunched JVM starts the bundle again instead of looking for Main on the bundle's launcher classpath
- JavadocLayout removes blank lines between block tags, as IntelliJ does ("Blank lines: after parameter descriptions" and "after return tag" are off by default)
- `--check` reports a formatter that fails (RuleCrash) instead of passing the file
- JavadocLayout writes one space after `@return`, as IntelliJ does

## 0.4.0

### Minor Changes

- New lint rule NonJavadocComment, off by default: reports `//` and `/* */` comments and leaves Javadoc, suppression comments and comment markers in literals alone; its fix removes the comment (offered, not applied)

### Patch Changes

- IOStreamConstructor leaves a stream of a FileDescriptor (`new FileOutputStream(FileDescriptor.out)`) alone: `Files` cannot open one

## 0.3.1

### Patch Changes

- The second JVM that jpm-format starts no longer prints class data sharing warnings and errors (from JDK 25, an error on every run)
