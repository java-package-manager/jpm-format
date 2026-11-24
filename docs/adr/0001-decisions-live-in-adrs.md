# 0001. Decisions live in ADRs; the forked layout engine keeps its comments

Status: accepted. The fork is gone ([0006](0006-intellij-style-without-palantir.md)), so the rule about comments
now has no exception.

Status: accepted

## Decision

- jpm-format's own Java sources and tests have no `//` or `/* */` comments, as in jpm (jpm's ADR 0001). A
  non-obvious decision goes into an ADR here; Javadoc is not a comment.
- The lint is jpm-format's own rule `NonJavadocComment`, turned on as an error in `project.jsonc`, so the
  self-check (`jpm start -- --check`, in CI) fails on a comment. It replaced `checks/Comments.java` and its
  baseline.
- `src/dev/jpm/format/palantir/` is a fork of Palantir Java Format and is left out:
  - every forked file keeps its Apache 2.0 copyright header, as the license requires;
  - upstream fixes are picked up by comparing with the upstream commit
    (`third_party/palantir-java-format/UPSTREAM.md`), which only works while the forked files stay
    close to upstream's text;
  - jpm-format's own changes to the fork are marked `jpm-format:` in comments, which is how the fork
    keeps track of them.
