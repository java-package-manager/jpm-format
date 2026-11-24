# Roadmap

What jpm-format does not do yet. jpm's own plan is in its
[ROADMAP.md](https://github.com/java-package-manager/jpm/blob/main/ROADMAP.md) (Milestone 5: IntelliJ parity in
formatting and lint).

## Formatting

- [ ] **IntelliJ parity.** The rest of IntelliJ's default Javadoc formatting, and IntelliJ's code style options,
      as rules in `src/dev/jpm/format/intellij/`, measured against IntelliJ's own formatter on real projects
      (`checks/Parity.java`, see [CONTRIBUTING.md](CONTRIBUTING.md)). Of IntelliJ's default inspections, null checks
      and data flow are left. Open in the corpus: IntelliJ leaves some Javadocs with `<pre>` entirely as they are
      (54 of 76: no `<p>`, no alignment) and formats the others; what decides it is not known yet.
- [ ] **Spotless formatters, natively.** `formatter.style: "eclipse"` runs Spotless's Eclipse JDT step, and
      `"files"` runs Spotless's steps on files other than Java (ktfmt, ktlint, scalafmt, JSON, YAML, SQL and the
      generic text steps), with jpm providing the jars. Still to come: the steps that need npm or a native binary
      (Prettier, ESLint, Biome, clang-format, gofmt, shfmt, buf, black), and Flexmark, SortPom and Gherkin, whose
      configs have many options.
- [ ] **Java formatters from Git.** `"formatter": { "style": "..." }` takes `palantir@<version>`,
      `google-java-format[@<version>]`, the URL of a jar and `github:author/my-java-formatter[@version|#branch]` (a
      jpm project jpm builds, with Palantir's or google-java-format's `Formatter`, or a jpm-format rule set). Still to
      come: a `#branch` of Palantir or google-java-format themselves, which build with Gradle and Maven, not jpm.
- [ ] **No Palantir fork.** Use Palantir Java Format from its own repository instead of the fork in
      `src/dev/jpm/format/palantir/`. `formatter.style: "palantir@<version>"` already runs upstream's releases as
      they are. The fork stays for the IntelliJ style: 46 changes of its own (`jpm-format:` in the code), most of
      them the `keepLineBreaks` mode, that upstream would have to take first.

## Editors

- [ ] **`project.jsonc` in the language server.** `jpm format lsp` gives diagnostics, formatting and quick fixes
      for Java files; in `project.jsonc` it only reloads the config. Still to come: jpm's warnings and errors in
      `project.jsonc`, at the value they are about, from `jpm check` over jpm's protocol, as jpm-intellij shows them.

## Running

- [ ] **Native binary.** jpm starts extensions on a JVM today. `jpm build --native` builds `dist/jpm-format` (42 MB,
      26 s with GraalVM 21.0.2), but it fails on the first file, inside javac:
  - javac's file manager reads `java.home`, which a native image does not set (NullPointerException in
    `BaseFileManager.createLocations`); `-Djava.home=<a JDK>` gets past it, so the binary would need a JDK anyway;
  - javac's message bundles and classes such as `Log$DeferredDiagnosticHandler` are missing from the image. The
    configuration `jpm start --trace -- --check` records is not enough; javac's classes probably need to be
    initialized at run time (`--initialize-at-run-time=com.sun.tools.javac`) and its resources listed by hand.

## Elsewhere

- [ ] `jpm import` translates Spotless config (in jpm).
- [ ] The IntelliJ plugin runs `jpm format` on save (in jpm-intellij).
