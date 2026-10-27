# Phase 5B Real Component Migration

This revision replaces the previous color-scheme-only approach with visible UCRT Glass containers.

## Explicitly migrated

- Library search surface
- Library category tiles
- Library selection overlay
- Track rows and selected/playing rows
- Album grid cards and album list rows
- Bottom navigation and navigation rail
- Mini Player and ripping status panel
- DSRE setting and conversion cards
- Audio/output setting cards
- Spotify content cards
- Settings cards and common sections from Phase 5A

## Structural appearance

- Ambient theme-color radial layers are rendered behind Glass surfaces.
- Glass components provide their own border, highlight, corner, depth, content color, and role-specific alpha.
- Existing dialogs and bottom sheets inherit the overlay Material colors and shapes.
- Standard remains the opaque fallback.

Functional audio, USB DAC, DSRE DSP, MediaCodec, metadata, and library data logic are unchanged.
