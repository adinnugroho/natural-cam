# STYLE plan implementation status — 2026-10-04

## Implemented

- **Domain:** validated `StylePoint` coordinates in [-1, 1], versioned `StyleState`, eight style presets, and independent Bloom, Grain, and Saturation amounts.
- **Engine:** strength-scaled light denoise, then bounded tone/color/palette processors, then the signed saturation chroma scale and the independent linear-light highlight bloom, downstream of NATURAL; a saturation boost also buys a shifted-grid chroma-only denoise pass so colour noise does not grow with colour; strength 0 is an exact NATURAL identity and bloom survives it. Grain is separate from this chain: the pipeline applies it to the delivered ARGB pixels after the output transfer, so its equal-per-channel shift keeps colour exactly untouched.
- **Persistence/capture:** style state persists through DataStore and is threaded through final NATURAL capture; RAW remains unstyled.
- **Live preview:** bounded visual approximation follows the same StyleState while editing, and Saturation additionally recolours the camera feed itself through a display-space colour matrix (`RenderEffect`, API 31+); full-resolution capture uses the reference StyleEngine.
- **Style mode UI:** tapping the style logo replaces the normal camera chrome with a dedicated style workspace:
  - floating STYLE header with back control, the strength slider (percentage drawn inside the track), and inline preset chips;
  - compact 1:1 150dp colored grid pad;
  - grid-snapped coordinates at 0.1 steps;
  - one compact amount row beside the pad whose tag is a dropdown that picks which amount the slider edits (Bloom / Grain / Saturation), with the amount drawn inside the track for one-sided amounts and outside it for the signed Saturation row (centre-anchored fill and neutral tick), plus reset;
  - back returns to the normal camera workspace.
- **Safety:** palette shadow undertone is chroma-weighted and bounded; neutral pixels stay neutral. Top-bar preset actions are explicitly forwarded through the MainActivity action adapter.
- **Tests:** full unit suite and debug build pass. Device walkthrough previously verified style mode, pad dragging, strength retention, and capture.

## Deliberate limits

- Preview is an efficient approximation, not pixel-identical full-resolution output.
- No LUT import, vignette, semantic masks, beauty processing, cloud processing, or style EXIF stamping.
- Palette directions remain deliberately restrained to avoid yellow/orange or cyan casts at extreme positions.
