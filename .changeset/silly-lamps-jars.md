---
"jpm-format": patch
---

The IntelliJ style wraps record components that would end past `java.lineWidth`, as IntelliJ's "wrap if long" does: before the first component whose line ends past the width, lined up with the first component. On Paper, the lines that differ from IntelliJ go from 301 to 238.
