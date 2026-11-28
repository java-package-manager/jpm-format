# Roadmap

What jpm-format does not do yet. jpm's own plan is in its
[ROADMAP.md](https://github.com/java-package-manager/jpm/blob/main/ROADMAP.md).

## Formatting

Parity is measured against IntelliJ's own formatter on real projects (`checks/Parity.java`, see
[CONTRIBUTING.md](CONTRIBUTING.md)); rules go in `src/dev/jpm/format/intellij/`.

- [ ] **`case X: {` blocks** (222 of the corpus's differing lines, in spring-core and jackson-databind): IntelliJ
  keeps the `{` on the line of its `case` or `default` label and the block's contents one indent in from the label;
  jpm-format moves the `{` to the next line and indents the block once more.
- [ ] **Differences found in Paper** (106 lines in Paper's own 3,096 files, `paper-api` and `paper-server/src/main`):
  - `};` after an anonymous class or enum body becomes `}`, a blank line and `;`; `{}` of a record or a `case` with
    a line before it becomes `{` and `}`; record patterns past the line width wrap after their `(`: about 20 lines;
  - indents, spacing and Javadoc shapes: about 80 lines.
- [ ] **Annotations and comments IntelliJ treats differently**: `@Deprecated /* c */ int f;` keeps the field on the
  annotation's line in IntelliJ (jpm-format breaks after the comment); a parameter after `@A // c` lines up with the
  first parameter (jpm-format puts it one continuation further in); a field without modifiers after a Javadoc
  comment and `@A // c` is one continuation indent in, in IntelliJ.
- [ ] **IntelliJ's code style options.**
- [ ] **A `#branch` of Palantir or google-java-format** as `formatter.style`; they build with Gradle and Maven,
  not jpm.

## Other file types

The goal: every file type that is common in a Java project is formatted by `jpm format` out of the box, chosen
by its extension, with no `"files"` entry needed; `"files"` still overrides the defaults. Out of the box today:
`.properties`, JSON, XML, YAML and TOML (jpm-format's own formatters), Markdown and Gherkin (Spotless); listed by hand in `"files"`, the Spotless steps that run
on a JVM: Kotlin, Scala, JSON, YAML, SQL, Markdown, Gherkin, Groovy, `pom.xml` sorting and plain text.

- [ ] **oxfmt first, for Markdown**: its own Rust engine formats Markdown (and JavaScript, TypeScript, CSS, YAML,
  TOML, HTML, GraphQL), and its standalone binary needs no Node for those. Once jpm can download it (below), it
  replaces `flexmark` as the Markdown default, and with it the Flexmark settings jpm-format maintains (front matter,
  code-block indents, trailing blank lines, running until stable). To check: YAML front matter, and
  `"embeddedLanguageFormatting": "off"` so code blocks are left alone.
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

  |               Files               |  Where they come from in a Java project   |                           Candidate                           |
  |-----------------------------------|-------------------------------------------|---------------------------------------------------------------|
  | XML                               | `pom.xml`, Spring, logback, Android, JAXB | done (jpm-format's own); `sortPom` for `pom.xml` in `"files"` |
  | `.properties`                     | Spring, i18n bundles, `gradle.properties` | done (jpm-format's own)                                       |
  | YAML                              | Spring, CI, Kubernetes, OpenAPI           | done (jpm-format's own; flow collections as written)          |
  | JSON, JSONC                       | config, test data, `project.jsonc`        | done (jpm-format's own, line breaks kept)                     |
  | Markdown                          | `README.md`, docs                         | done (`flexmark`, by default)                                 |
  | Groovy, Gradle                    | `build.gradle`, Spock tests               | `greclipse` in `"files"` (today)                              |
  | Kotlin, `.kts`                    | Kotlin code, `build.gradle.kts`           | `ktfmt` (today)                                               |
  | TOML                              | `libs.versions.toml`                      | done (jpm-format's own)                                       |
  | SQL                               | Flyway and Liquibase migrations           | `dbeaverSql` (today)                                          |
  | HTML, CSS, JavaScript, TypeScript | web front ends, Thymeleaf templates       | Biome or oxfmt                                                |
  | Shell                             | `mvnw`, `gradlew`, scripts                | shfmt                                                         |
  | Dockerfile                        | containers                                | dprint                                                        |
  | Protobuf, gRPC                    | `.proto`                                  | buf or clang-format                                           |
  | GraphQL                           | schemas                                   | Biome                                                         |
  | Gherkin                           | Cucumber `.feature`                       | done (`gherkinUtils`, by default)                             |

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
