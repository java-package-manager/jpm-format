# jpm-format

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
