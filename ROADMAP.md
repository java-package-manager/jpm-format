# Roadmap

What jpm-format does not do yet. jpm's own plan is in its
[ROADMAP.md](https://github.com/java-package-manager/jpm/blob/main/ROADMAP.md).

## Formatting

Parity is measured against IntelliJ's own formatter on real projects (`checks/Parity.java`, see
[CONTRIBUTING.md](CONTRIBUTING.md)); rules go in `src/dev/jpm/format/intellij/`.

- [ ] **IntelliJ leaves the Javadoc of some files alone.** It formats Javadocs with `<pre>` like the others, but in
      `ObjectUtils.java` (commons-lang3, 140 of the lines that differ) it formats none of them; what decides it is
      not known yet.
- [ ] **The rest of IntelliJ's default Javadoc formatting.**
- [ ] **IntelliJ's code style options.**
- [ ] **A `#branch` of Palantir or google-java-format** as `formatter.style`; they build with Gradle and Maven,
      not jpm.

## Other file types

The goal: every file type that is common in a Java project is formatted by `jpm format` out of the box, chosen
by its extension, with no `"files"` entry needed; `"files"` still overrides the defaults. Out of the box today:
`.properties`, JSON, XML and TOML (jpm-format's own formatters); listed by hand in `"files"`, the Spotless steps that run
on a JVM: Kotlin, Scala, JSON, YAML, SQL, Groovy's semicolons and plain text.

- [ ] **Native formatters, downloaded by jpm**: a binary per platform, pinned by version and SHA-256, with no npm
      or Node needed. Candidates:
  - [Biome](https://biomejs.dev/internals/language-support): one binary for JavaScript, TypeScript, JSX, JSON,
    JSONC, CSS and GraphQL; HTML, Vue, Svelte and Astro still experimental.
  - [oxfmt](https://oxc.rs/docs/guide/usage/formatter/language-support.html): Prettier-compatible, JavaScript and
    TypeScript in its own engine, other languages through a bundled Prettier (to check: whether that needs Node).
  - shfmt, clang-format, buf, gofmt, black, as Spotless has them.

  Which of Biome and oxfmt is the default for which language is open. Another way, to check: dprint's Wasm
  plugins (TOML, Markdown, Dockerfile, CSS, HTML, YAML) on a Wasm runtime in Java, which needs no binary per
  platform.
- [ ] **Defaults by file type**:

  | Files | Where they come from in a Java project | Candidate |
  |---|---|---|
  | XML | `pom.xml`, Spring, logback, Android, JAXB | done (jpm-format's own); SortPom for `pom.xml` as a step |
  | `.properties` | Spring, i18n bundles, `gradle.properties` | done (jpm-format's own) |
  | YAML | Spring, CI, Kubernetes, OpenAPI | one that keeps comments: Biome, oxfmt or dprint |
  | JSON, JSONC | config, test data, `project.jsonc` | done (jpm-format's own, line breaks kept) |
  | Markdown | `README.md`, docs | Flexmark (Spotless, JVM), oxfmt or dprint |
  | Groovy, Gradle | `build.gradle`, Spock tests | greclipse (Spotless, JVM) |
  | Kotlin, `.kts` | Kotlin code, `build.gradle.kts` | `ktfmt` (today) |
  | TOML | `libs.versions.toml` | done (jpm-format's own) |
  | SQL | Flyway and Liquibase migrations | `dbeaverSql` (today) |
  | HTML, CSS, JavaScript, TypeScript | web front ends, Thymeleaf templates | Biome or oxfmt |
  | Shell | `mvnw`, `gradlew`, scripts | shfmt |
  | Dockerfile | containers | dprint |
  | Protobuf, gRPC | `.proto` | buf or clang-format |
  | GraphQL | schemas | Biome |
  | Gherkin | Cucumber `.feature` | Spotless `gherkinUtils` |
- [ ] **Spotless steps whose configs have many options:** Flexmark, SortPom and Gherkin.
- [ ] **Prettier and ESLint** with their plugins, for projects that already use them; these need npm.

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
