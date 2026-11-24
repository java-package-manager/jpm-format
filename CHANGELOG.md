# jpm-format

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
