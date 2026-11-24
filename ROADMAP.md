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
- [ ] **No Palantir fork.** Use Palantir Java Format from its own repository instead of the fork in
      `src/dev/jpm/format/palantir/`. The fork stays for the IntelliJ style: 46 changes of its own (`jpm-format:`
      in the code), most of them the `keepLineBreaks` mode, that upstream would have to take first.

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
