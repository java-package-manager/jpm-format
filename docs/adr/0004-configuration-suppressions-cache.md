# 0004. Configuration, suppressions and the cache

Status: accepted

## Decision

- Configuration comes from `jpm-format.jsonc` at the root and in folders below it, up to 6 deep; for a file,
  the deepest folder that has one wins (`Config`). Amended: it used to come from `project.jsonc`
  (`extensions.format`), which now configures no formatter, so jpm's manifest and the formatter's config stay apart.
- A suppression covers the line it is on and all of the element that starts on that line, so
  reformatting cannot move code out of its reach (`Suppressions`).
- Type attribution compiles a file as jpm does: a named module reads its dependencies from the module
  path; other folders (such as tests) see the classpath and the non-modular sources only (`Project`).
- The cache is best effort. A broken cache is a cold one; failures to write it are ignored (`Cache`).
- Custom rules are compiled into a shared folder; when another run creates it first, that run's output is
  used (`CustomRules`).
