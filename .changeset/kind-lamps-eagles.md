---
"jpm-format": minor
---

The IntelliJ style now runs on jpm-format's own layout engine instead of the Palantir fork. It is closer to IntelliJ: it moves an if, loop or switch-rule body that would end past lineWidth to the next line, lines up multi-line case labels, indents continuation lines from the nearest enclosing part that starts a line, and moves text blocks to the continuation indent. Expect some reformatting on the first run. Range formatting in the language server formats only the lines it touches.
