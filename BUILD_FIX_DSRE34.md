# DSRE34 compile fixes

- Preset import now parses `harmonic` as a finite Float in the supported 0.0..1.0 range, matching `DsreUserPreset.harmonic`.
- Removed the explicit `androidx.compose.foundation.layout.weight` import. Calls to `Modifier.weight` remain inside `Row` content and resolve through `RowScope`.
