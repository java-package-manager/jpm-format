# 0005. Decisions in individual rules

Status: accepted

## Decision

- `UnusedPrivateMember`: a record's components appear as private fields; they are its API, not unused
  state.
- `UnnecessaryModifier`: local classes are not members.
- `LicenseHeader`: in a file without a package, the other leading comments (such as a class's Javadoc)
  are kept.
- `JavadocLayout`: when IntelliJ (2026.3) changes the Javadoc of a top-level type that has a `@formatter:off`
  comment, it leaves the Javadoc between the two as it is, and formats it when it runs again. jpm-format formats
  it the first time: a file `jpm format` wrote must pass `jpm format --check`, so its output may not depend on
  whether the type's Javadoc was formatted already. This is 140 of the lines the parity check counts, in
  commons-lang3's `ObjectUtils`.
