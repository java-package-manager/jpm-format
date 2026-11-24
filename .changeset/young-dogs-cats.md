---
"jpm-format": minor
---

The shared lineWidth, indentWidth and maxBlankLines always carry over to every file type, their defaults too: JSON, YAML, XML, TOML and .properties are indented 4 unless their section ("json": { "formatter": { "indentWidth": 2 } }) sets them apart.
