### Module `intellij.platform.backend.baseline`

**Deprecated. This module is temporary. Do not use it.**

A backend cannot be a baseline. This marker exists only to gate `intellij.platform.kernel.backend.baseline`.
JetBrains Light needs this module before it gets a correct home.

The marker is an environment-configured module. `ProductModeCapabilities.configureProductModeModules` sets its availability:

| MONOLITH | BACKEND | FRONTEND | LIGHT_REMOTE | LIGHT_WITH_RD_CONNECTION | LIGHT_MONOLITH | LANGUAGE_SERVER |
|---|---|---|---|---|---|---|
| + | + | - | - | - | + | + |

This is `intellij.platform.backend` plus `LIGHT_MONOLITH`.
A RUNTIME dependency on this module keeps a module out of a frontend process.
The frontend process registers its own `KernelService`.

Remove this module when the module above moves to a correct place.
