---
"jpm-format": patch
---

JSON and JSONC: in the default `"keep"` style, an object or array that makes a line end past `lineWidth` (120) gets every value on a line of its own and its closing bracket too, the outermost first, as IntelliJ's "chop down if long" does; lines within the width keep their breaks.
