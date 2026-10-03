# Vendor Compatibility Policy (AGENTS 58)

All application behavior is capability-driven. Vendor- or model-specific code
belongs exclusively in `compat/CompatRegistry.kt`.

## Adding a device quirk

A quirk may be added only with **all** of the following:

1. a reproducible issue (steps, observed vs expected behavior);
2. the affected device and Android build fingerprint;
3. a technical explanation of the platform defect;
4. an isolated implementation in `CompatRegistry` (no `if (MANUFACTURER == …)`
   elsewhere in the codebase);
5. a reference from `docs/COMPAT.md` (this file) listing the quirk ID;
6. a test where practical.

Quirks must never replace capability discovery entirely; they only adjust
behavior when metadata is known to lie on a specific build (SPEC §12).

## Current quirks

None. Registry is intentionally empty until real-device testing produces
evidence (AGENTS 59).
