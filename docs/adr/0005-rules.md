# 0005. Decisions in individual rules

Status: accepted

## Decision

- `UnusedPrivateMember`: a record's components appear as private fields; they are its API, not unused
  state.
- `UnnecessaryModifier`: local classes are not members.
- `LicenseHeader`: in a file without a package, the other leading comments (such as a class's Javadoc)
  are kept.
