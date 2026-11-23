# Roadmap

What jpm-format does not do yet. jpm's own plan is in its
[ROADMAP.md](https://github.com/java-package-manager/jpm/blob/main/ROADMAP.md) (Milestone 5: IntelliJ parity,
then a language server).

## Formatting

- [ ] **IntelliJ parity.** The rest of IntelliJ's default Javadoc formatting, and IntelliJ's code style options,
      as rules in `src/dev/jpm/format/intellij/`, measured against IntelliJ's own formatter on real projects
      (`checks/Parity.java`, see [CONTRIBUTING.md](CONTRIBUTING.md)). Of IntelliJ's default inspections, null checks
      and data flow are left. Open in the corpus: IntelliJ leaves some Javadocs with `<pre>` entirely as they are
      (54 of 76: no `<p>`, no alignment) and formats the others; what decides it is not known yet.
- [ ] **Spotless formatters, natively.** Run Spotless's formatter steps as jpm-format formatters, configured in
      `extensions.format`.
- [ ] **Java formatters from Git.** `"formatter": { "style": "..." }` takes `palantir@<version>` and
      `google-java-format[@<version>]` (released jars, resolved by jpm). Still to come: a `#branch` of them, a URL of
      a jar release, and `github:author/my-java-formatter[@version|#branch]`, any formatter from a Git repository, as a
      jpm-format extension (the rule API) or as a Spotless step.
- [ ] **No Palantir fork.** Use Palantir Java Format from its own repository instead of the fork in
      `src/dev/jpm/format/palantir/`. Needs native Spotless support first.

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
