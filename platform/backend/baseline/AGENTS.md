**Critical:** `intellij.platform.backend.baseline` is deprecated and temporary. Read [README.md](./README.md).

- Do not add a dependency on this module. Use `intellij.platform.backend` or a mode-neutral module.
- Only `intellij.platform.kernel.backend.baseline` may depend on it.
- Do not add content, services, or extensions to this module.
