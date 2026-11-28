---
"jpm-format": minor
---

The format config is read only from `jpm-format.jsonc` (at the project root, and in folders below it for the files there); `"extensions": { "format": … }` in `project.jsonc` is no longer read, and `extends` targets are plain configs. Move an existing `extensions.format` section into `jpm-format.jsonc`.
