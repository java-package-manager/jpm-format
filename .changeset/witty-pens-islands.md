---
"jpm-format": patch
---

Formatting fixes found on a real project: imports put `javax.*` before `java.*`, as IntelliJ's default import layout does (a group's imports follow the order of its patterns); a switch rule's `{` stays after its `->`, and a record pattern past `java.lineWidth` wraps after its `(` with its `)` on a line of its own, its components wrapping if still too long; empty `case` blocks and empty record bodies after a multi-line header are split into `{` and `}`, and an empty enum keeps `{}`, as IntelliJ does.
