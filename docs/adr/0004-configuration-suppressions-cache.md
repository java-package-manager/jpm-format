# 0004. Configuration, suppressions and the cache

Status: accepted

## Decision

- Configuration comes from the root's `project.jsonc` and from module manifests below the root
  (workspaces), up to 6 folders deep. For a file, the deepest module that contains it wins (`Config`).
- A suppression covers the line it is on and all of the element that starts on that line, so
  reformatting cannot move code out of its reach (`Suppressions`).
- Type attribution compiles a file as jpm does: a named module reads its dependencies from the module
  path; other folders (such as tests) see the classpath and the non-modular sources only (`Project`).
- The cache is best effort. A broken cache is a cold one; failures to write it are ignored (`Cache`).
- Custom rules are compiled into a shared folder; when another run creates it first, that run's output is
  used (`CustomRules`).
