# Roadmap

What jpm-format does not do yet. jpm's own plan is in its
[ROADMAP.md](https://github.com/java-package-manager/jpm/blob/main/ROADMAP.md).

## Formatting

Parity is measured against IntelliJ's own formatter on real projects (`checks/Parity.java`, see
[CONTRIBUTING.md](CONTRIBUTING.md)); rules go in `src/dev/jpm/format/intellij/`.

- [ ] **Javadocs with `<pre>`.** IntelliJ leaves some of them entirely as they are (54 of 76 in the corpus: no
      `<p>`, no alignment) and formats the others; what decides it is not known yet.
- [ ] **The rest of IntelliJ's default Javadoc formatting.**
- [ ] **IntelliJ's code style options.**
- [ ] **Spotless steps that need npm or a native binary:** Prettier, ESLint, Biome, clang-format, gofmt, shfmt,
      buf, black.
- [ ] **Spotless steps whose configs have many options:** Flexmark, SortPom and Gherkin.
- [ ] **A `#branch` of Palantir or google-java-format** as `formatter.style`; they build with Gradle and Maven,
      not jpm.
- [ ] **The IntelliJ style's own layout engine; no Palantir in the codebase**
      ([ADR 0006](docs/adr/0006-intellij-style-without-palantir.md)). A whitespace engine in
      `src/dev/jpm/format/intellij/` replaces the fork in `src/dev/jpm/format/palantir/` for the `intellij` style:
      indents from javac's tree, spaces between tokens, line breaks kept. It becomes the default once
      `checks/Parity.java` counts no more than the baseline; then the fork, its `jpm-format:` changes and upstream's
      test data are deleted, and `"palantir"` runs from Palantir's own jar as an external formatter.

## Lint

- [ ] IntelliJ's default inspections for null checks and data flow.

## Running

- [ ] **Native binary.** jpm starts extensions on a JVM today. `jpm build --native` builds `dist/jpm-format` (42 MB,
      26 s with GraalVM 21.0.2), but it fails on the first file, inside javac:
  - javac's file manager reads `java.home`, which a native image does not set (NullPointerException in
    `BaseFileManager.createLocations`); `-Djava.home=<a JDK>` gets past it, so the binary would need a JDK anyway;
  - javac's message bundles and classes such as `Log$DeferredDiagnosticHandler` are missing from the image. The
    configuration `jpm start --trace -- --check` records is not enough; javac's classes probably need to be
    initialized at run time (`--initialize-at-run-time=com.sun.tools.javac`) and its resources listed by hand.
