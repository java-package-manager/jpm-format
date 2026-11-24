---
"jpm-format": minor
---

Settings per file type are written "default": { ... } for every type without its own, and "java": { ... }, "json": { ... } (and xml, yaml, toml, properties) for one type; each type keeps its own defaults (JSON and YAML 2, the others 4) unless one of them sets it.
