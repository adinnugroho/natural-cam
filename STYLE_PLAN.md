# STYLE_PLAN.md

## 1. Purpose

This document defines the product and implementation plan for the **Style** system of the Android natural-camera application.

The Style system gives users creative control over the final rendering **without replacing or corrupting the NATURAL image pipeline**.

The intended mental model is:

```text
Technically correct NATURAL image
            ↓
        Style Engine
            ↓
   Creative interpretation
            ↓
        Final output
```

The Style system is **not** responsible for fixing:

- incorrect white balance;
- cyan/green color casts caused by pipeline bugs;
- incorrect exposure normalization;
- broken camera color transforms;
- gamma/transfer-function mistakes;
- bad RAW normalization.

Those problems must be fixed in the core NATURAL pipeline first.

---

## 2. Authority

Document authority remains:

```text
PRD.md
  ↓
SPEC.md
  ↓
AGENTS.md
  ↓
STYLE_PLAN.md
  ↓
Implementation
```

If this plan conflicts with `PRD.md`, `SPEC.md`, or `AGENTS.md`, the higher-authority document wins.

This document does not redefine the core product philosophy.

---

## 3. Product Goal

Style should make the camera more expressive while preserving the project's identity:

> Natural photography first, creative rendering second.

The user should be able to create a recognizable visual character **before capture**, while still receiving an image that remains photographic and believable.

Style should behave more like a restrained photographic rendering system than a traditional social-media filter.

---

## 4. Core Principles

### 4.1 NATURAL Is the Baseline

Every style begins from the output intent of `NATURAL`.

Conceptually:

```text
PURE
  ↓
Correct color development
  ↓
NATURAL
  ↓
STYLE
```

A style must never become a hidden workaround for a broken NATURAL pipeline.

### 4.2 Non-Destructive Intent

Style should modify rendering parameters rather than destructively applying arbitrary overlays.

Prefer:

- tone-curve changes;
- controlled chroma shaping;
- hue-aware palette transforms;
- selective warmth/coolness;
- highlight/shadow color character;
- restrained contrast changes.

Avoid:

- flat color overlays;
- arbitrary RGB multipliers;
- fake film grain by default;
- aggressive LUT stacking;
- uncontrolled saturation boosts;
- heavy vignette as default behavior;
- hidden beauty processing.

### 4.3 Preview Must Represent Capture

The live preview should approximate the final styled output closely enough that users can compose and choose style intentionally.

Where the preview can recolour the feed directly it should: `Saturation` is shown by
a hue-preserving colour matrix on the camera view (display space, API 31+), because a
signed colour change with no preview would mean composing blind. The matrix and the
capture share one `SATURATION_RANGE`, so the slider means the same thing in both,
even though the capture applies its scale in linear light.

Perfect pixel identity is not required, but users should not see a dramatically different result after capture.

### 4.4 Styles Must Be Bounded

The Style system should not allow normal UI interactions to push the image into obviously broken rendering.

All Style Pad coordinates map into **safe, bounded parameter ranges**.

Extreme artistic modes may be added later as separate features, but are not part of Style v1.

### 4.5 Independent Bloom control

Bloom is an independent control in the Style workspace, not a style preset: it is
usable with any selected style (and with the pads switched off) and is never part of
the default rendering or PURE.
It peak-pools the pixels above a bounded linear-light threshold into a mask at 1/16
resolution, blurs that mask there (the glow spans ~1% of the frame width, so a
full-resolution blur of the same radius would be ~256× the work), and bilinearly
upsamples it back as added light. Amount 0 — or an image without highlights — is an
exact no-op; the amount is a direct 0–1 slider with its own value, independent of the
pad `Strength` control.


---

## 5. User Experience

Primary interaction:

```text
Camera
  ↓
Tap Style button
  ↓
Style panel opens
  ↓
Choose style dimension
  ↓
Move Style Pad point
  ↓
Adjust strength if desired
  ↓
Close panel
  ↓
Capture
```

The user should not need to understand color science.

The UI exposes intuitive visual controls while the implementation maps them into technically safe processing parameters.

---

## 6. Style UI Structure

Initial Style interface:

```text
┌───────────────────────────────┐
│ TONE  -12   COLOR  +08        │
│ PALETTE  +24                  │
│                               │
│        STYLE / UNDERTONE      │
│                               │
│      ┌─────────────────┐      │
│      │                 │      │
│      │        ●        │      │
│      │                 │      │
│      └─────────────────┘      │
│                               │
│ Strength ━━━━━━━━━●━━━━       │
│                               │
│ Reset                    Done │
└───────────────────────────────┘
```

The exact visual design belongs to `DESIGN.md` if one exists.

This document defines behavior, not visual styling.

---

## 7. Main Style Dimensions

Style v1 contains three dimensions:

```text
TONE
COLOR
PALETTE
```

Each dimension owns its own 2D Style Pad.

A separate `Strength` control adjusts how strongly the complete style is applied.
`Saturation`, `Bloom`, and `Grain` are not style dimensions and not presets: they
are independent amount sliders that work alongside any style (and at `Strength` 0).
Each affects only its own parameter. `Bloom` and `Grain` are bounded 0–1 amounts
where 0 is an exact no-op; `Saturation` is signed −1…+1, because for it "no change"
has to sit in the middle: 0 is exactly the settled colour, negative pulls chroma
toward gray (0.4× at the end of the track), positive pushes it (1.6×), and neutral
greys stay neutral at either end.

A signed control must also *look* signed: one-sided rows keep the left-to-right fill
that matches "0 = off", while `Saturation` draws its own centre-anchored fill and a
neutral tick, and shows its readout outside the track. A boost widens the chroma it
scales, noise included, so it pays a second chroma-only denoise pass on a shifted
2x2 grid - inside the chroma resolution the JPEG output discards anyway - and the
saved colour gets stronger without the colour noise growing with it.

`Grain` is dynamic rather than a fixed overlay: its amplitude follows a midtone
bell and fades out where the image already carries local detail or edges, so the
grain reads as texture in smooth areas instead of being stamped over everything.
Its structure is pixel-scale — one lattice octave near 1.6 px interpolated linearly
(no smoothing, which reads as blur) over per-pixel speckle — with the amplitude only
gently nudged by a small patch field and, where the grain has to survive 8-bit
rounding, a dithered shift rather than a plain one.
It is also applied to the *delivered* pixels — after tone, gamut, and the output
transfer curve — rather than inside the linear working space, because only there
can every channel take the same shift and leave the image's colour exactly
untouched.

The style chain also starts with a light denoise scaled by `Strength` (chroma
stronger than luminance), so the styled result is a little cleaner before tone and
bloom are applied. It disappears with `Strength`:

```text
style chain = denoise(strength) → TONE/COLOR/PALETTE pads(strength) → Saturation → Bloom
delivered   = … → gamut → output transfer → Grain
```

The pads therefore change no tone or color at pad center, but the chain is not
bit-identical to NATURAL above `Strength` 0; `Strength` 0 still is.

---

## 8. TONE

### 8.1 Purpose

`TONE` controls the photographic tonal character without changing camera exposure itself.

It must not directly manipulate AE or sensor exposure.

### 8.2 Pad Axes

Recommended v1 axes:

```text
                 BRIGHTER MIDTONES
                        ↑
                        │
                        │
        SOFT  ←─────────┼─────────→  HARD
                        │
                        │
                        ↓
                  DEEPER TONALITY
```

Interpretation:

```text
X:
-1.0 = softer contrast
 0.0 = NATURAL baseline
+1.0 = firmer contrast

Y:
+1.0 = lifted midtone character
 0.0 = NATURAL baseline
-1.0 = deeper / moodier tonal character
```

### 8.3 TONE Parameters

The Tone pad may influence bounded versions of:

```text
midtoneLift
contrastPivot
contrastStrength
toeStrength
blackPointOffset
shoulderStrength
highlightCompression
```

Tone must not become a generic brightness slider.

### 8.4 Tone Safety Rules

Tone adjustments must preserve:

- monotonic tone response;
- valid black point;
- smooth highlight shoulder;
- no negative luminance;
- no severe highlight inversion;
- no HDR-like shadow flattening.

---

## 9. COLOR

### 9.1 Purpose

`COLOR` adjusts broad color character while preserving realistic scene color relationships.

This is a creative adjustment **after technically correct white balance and camera color transformation**.

### 9.2 Pad Axes

Recommended v1 axes:

```text
                   RICHER
                     ↑
                     │
                     │
       COOL  ←───────┼───────→  WARM
                     │
                     │
                     ↓
                   MUTED
```

```text
X:
-1.0 = cooler rendering
 0.0 = NATURAL baseline
+1.0 = warmer rendering

Y:
+1.0 = richer color
 0.0 = NATURAL baseline
-1.0 = muted color
```

### 9.3 COLOR Parameters

The Color pad may influence:

```text
creativeTemperatureOffset
creativeTintOffset
chromaScale
selectiveChromaCompression
skinProtectionStrength
highlightChromaProtection
```

These are creative offsets only.

They must not replace capture/AWB correction.

### 9.4 Color Safety Rules

Avoid:

- global RGB channel hacks;
- strong orange skin shifts;
- neon greens;
- cyan shadows caused by simple multipliers;
- clipping saturated channels;
- style changes that destroy neutral objects completely.

Even a warm style should leave genuinely neutral surfaces broadly believable.

---

## 10. PALETTE

### 10.1 Purpose

`PALETTE` controls the relationship between hue families rather than merely increasing saturation.

This is the most expressive Style dimension.

It should create recognizable rendering character while remaining photographic.

### 10.2 Pad Concept

Recommended v1 directional palette:

```text
                       GOLD
                        ↑
                        │
                        │
         GREEN  ←───────┼───────→  ROSE
                        │
                        │
                        ↓
                       BLUE
```

This does **not** mean applying a gold/green/rose/blue color overlay.

Each direction changes multiple hue families in a controlled way.

### 10.3 Palette Direction Intent

**GOLD**

```text
warm yellows slightly richer
orange tones slightly warmer
blues slightly restrained
skin remains protected
```

**BLUE**

```text
cool tones gain presence
warm colors remain believable
shadows may become slightly cooler
skin must not become cyan
```

**GREEN**

```text
greens become slightly more organic
foliage hue may shift subtly
warm tones remain stable
```

**ROSE**

```text
reds / magentas become slightly richer
skin warmth may receive a restrained lift
blues should not become purple globally
```

### 10.4 Palette Implementation

Palette should preferably use hue-aware transforms such as:

- hue-vs-hue curves;
- hue-vs-saturation curves;
- hue-vs-luminance curves;
- bounded perceptual color-space adjustments;
- controlled matrix + nonlinear mapping where justified.

Avoid a simple full-frame tint overlay.

---

## 11. UNDERTONE MODE

`Undertone` may be used as the user-facing name for the Palette pad or as a future dedicated sub-mode.

If implemented separately, Undertone should primarily influence the tonal-color relationship of:

```text
shadows
lower midtones
midtones
```

while preserving highlight neutrality better than a whole-frame temperature adjustment.

Conceptually:

```text
Correct WB
   ↓
Correct Color Transform
   ↓
NATURAL Tone
   ↓
Undertone / Style Color Character
   ↓
Final
```

Undertone must never be used to repair incorrect white balance.

---

## 12. Strength

### 12.1 Purpose

`Strength` controls how far the image moves from NATURAL toward the selected style.

Range:

```text
0.0 ... 1.0
```

UI representation:

```text
0%                         100%
NATURAL ━━━━━━━━━━━━━━━● FULL STYLE
```

### 12.2 Parameter Interpolation

Prefer parameter interpolation:

```kotlin
finalValue = naturalValue +
    (styledValue - naturalValue) * strength
```

rather than blending the final styled image over the NATURAL image as if it were a Photoshop opacity layer.

Image blending may be used only when technically justified.

### 12.3 Strength Zero Rule

At:

```text
strength = 0
```

the output must be functionally equivalent to NATURAL.

Style must not leave hidden side effects at zero strength.

The independent amounts (`Saturation`, `Bloom`, `Grain`) are not hidden side
effects: they are explicit user settings shown in the workspace, and they keep
applying at `Strength` 0 the same way they apply at `Strength` 1.

---

## 13. Coordinate Model

Each Style Pad uses normalized coordinates:

```text
x ∈ [-1.0, +1.0]
y ∈ [-1.0, +1.0]
```

Center:

```text
(0, 0)
```

means neutral/default behavior for that Style dimension.

### 13.1 Domain Model

Recommended model:

```kotlin
data class StylePoint(
    val x: Float,
    val y: Float
)

data class StyleState(
    val tone: StylePoint = StylePoint(0f, 0f),
    val color: StylePoint = StylePoint(0f, 0f),
    val palette: StylePoint = StylePoint(0f, 0f),
    val bloom: Float = 0f,
    val strength: Float = 1f
)

```

Values must be clamped at domain boundaries.

---

## 14. Style Presets

Style v1 should support named presets built from `StyleState`.

Example conceptual presets:

Natural
Soft
Warm
Gold
Cool
Muted
Rich
Deep


These names are placeholders until product/design decisions are finalized.

`Warm Street` (`warm_street`) is the exception: its look is specified in
`FILM_STYLE.md` and it is shipped as the ninth built-in preset — a restrained
warm street-photography / filmic character (firm black point, dense warm-neutral
midtones, soft warm highlights, restrained blues, organic greens) built only from
pads.

A preset is only a starting point.

Users can move the Style Pads after choosing a preset.

### 14.1 Preset Model

```kotlin
data class StylePreset(
    val id: String,
    val name: String,
    val state: StyleState,
    val version: Int
)
```

Do not encode style behavior using arbitrary filter names hidden inside UI code.

---

## 15. Pipeline Placement

Recommended architecture:

```text
Capture Source
   ↓
Normalization
   ↓
White Balance
   ↓
Camera Color Transform
   ↓
Exposure Normalization
   ↓
NATURAL Base Tone
   ↓
────────────────────────────
        STYLE ENGINE
────────────────────────────
   ↓
Tone Style
   ↓
Color Style
   ↓
Palette / Undertone
   ↓
Final Gamut Mapping
   ↓
Output Transfer Function
   ↓
Encode
```

Exact ordering may change after testing, but Style must remain downstream of technical correction.

---

## 16. Style Engine

Recommended abstraction:

```kotlin
interface StyleEngine {
    suspend fun apply(
        image: WorkingImage,
        style: StyleState,
        context: StyleContext
    ): WorkingImage
}
```

Style engine must not depend on Compose UI.

### 16.1 Internal Components

Possible components:

```text
StyleEngine
├── ToneStyleProcessor
├── ColorStyleProcessor
├── PaletteStyleProcessor
├── StyleParameterResolver
└── StyleInterpolator
```

---

## 17. StyleParameterResolver

The XY coordinate must not map directly to arbitrary magic constants throughout the codebase.

Centralize mapping:

```text
StylePoint
   ↓
StyleParameterResolver
   ↓
Bounded Processing Parameters
```

Example:

```kotlin
data class ResolvedToneStyle(
    val midtoneLift: Float,
    val contrastStrength: Float,
    val toeStrength: Float,
    val shoulderStrength: Float
)
```

---

## 18. Mapping Curves

Pad coordinate mapping does not need to be linear.

A nonlinear response is recommended so the center offers fine control.

Conceptual behavior:

```text
small effect near center
stronger response toward edges
```

Possible mapping:

```kotlin
fun shape(value: Float): Float {
    return sign(value) * value * value
}
```

The final function should be selected through interaction testing.

---

## 19. Preview Architecture

Style preview should preferably run on a lower-resolution preview/analysis stream.

Conceptually:

```text
Camera Preview Frame
      ↓
Preview Color Path
      ↓
Approximate NATURAL
      ↓
Style Engine
      ↓
Display
```

Capture path:

```text
Full Resolution Capture
      ↓
Full NATURAL Pipeline
      ↓
Same StyleState
      ↓
Full Style Engine
      ↓
Final Image
```

The same `StyleState` must drive both preview and final capture.

---

## 20. Preview Performance

Dragging the Style Pad must feel immediate.

Target behavior:

- preview updates continuously;
- no camera session restart;
- no full-resolution processing on every drag event;
- no shutter blocking caused by UI interaction;
- no large memory allocation per pointer movement.

Heavy style calculations should use efficient preview representations.

---

## 21. UI Interaction Rules

Style Pad should support:

- tap anywhere to move point;
- drag continuously;
- clamp point to pad bounds;
- reset to center;
- optional double-tap reset;
- accessible numeric/state representation internally.

Haptic feedback may be used at:

- center crossing;
- major preset snap points;
- reset.

Haptics are optional.

---

## 22. Style Button Behavior

Main camera screen should expose one compact Style entry point.

It should not permanently occupy large portions of the viewfinder.

The Style panel should appear as an anchored popup, bottom sheet, or dedicated overlay depending on `DESIGN.md`.

---

## 23. Persistence

The selected style may persist across app launches if product behavior chooses so.

Recommended stored values:

```text
selected preset ID
Tone StylePoint
Color StylePoint
Palette StylePoint
Bloom strength
Strength
style version
```

Use DataStore.

---

## 24. Versioning

Style interpretation must be versioned.

Reason: if `StylePoint(0.5, 0.4)` produces different rendering after algorithm tuning, saved user styles may unexpectedly change.

Recommended:

```kotlin
data class StyleState(
    val version: Int,
    // ...
)
```

Major mapping changes require migration or intentional preset-version changes.

---

## 25. Metadata

Optional future metadata may record:

```text
style preset ID
style version
Tone coordinates
Color coordinates
Palette coordinates
Strength
```

Do not overload standard EXIF fields incorrectly.

This is not required for MVP.

---

## 26. RAW Behavior

RAW/DNG output must remain independent from creative Style rendering by default.

Recommended behavior:

```text
RAW + Final

RAW   = sensor-oriented DNG
Final = NATURAL + selected Style
```

Do not bake Style into the RAW sensor data.

---

## 27. PURE Behavior

Style is primarily designed for NATURAL output.

For v1, Style should not silently alter the semantic meaning of PURE.

Recommended MVP policy:

```text
PURE + Style
→ Style disabled
```

or:

```text
activating Style automatically uses NATURAL base
```

The final choice must be reflected in PRD/SPEC before implementation.

Do not implement ambiguous PURE + Style behavior.

---

## 28. SYSTEM Behavior

SYSTEM represents OEM/platform rendering.

Applying custom Style on top of SYSTEM may create inconsistent results across vendors.

Recommended MVP policy:

```text
SYSTEM + Style
→ Style disabled
```

Style v1 should be designed around the controlled NATURAL pipeline.

---

## 29. Image Quality Rules

Style must never intentionally introduce:

- skin smoothing;
- face reshaping;
- artificial detail;
- HDR halos;
- posterization;
- clipped saturated channels;
- severe color crossover;
- unstable neutral rendering;
- extreme shadow color contamination.

---

## 30. Skin Protection

Creative color adjustments may optionally include restrained skin-tone protection.

This means preserving plausible skin hue/chroma relationships.

It does **not** mean:

- detecting faces for beauty processing;
- smoothing skin;
- whitening skin;
- reshaping faces.

If semantic segmentation is not required, avoid it in v1.

Prefer hue-range-aware color handling.

---

## 31. Gamut Safety

Style processing must remain gamut-aware.

Before output:

```text
styled working RGB
   ↓
gamut mapping
   ↓
output color space
```

Avoid naive channel clipping where possible.

Highly saturated colors should compress gracefully.

---

## 32. Numerical Safety

Tests must ensure:

- no NaN;
- no Infinity;
- valid finite channels;
- monotonic tone curves where required;
- bounded style coordinates;
- valid final pixel ranges.

---

## 33. Testing Matrix

At minimum evaluate Style on:

**Daylight:** blue sky, foliage, neutral concrete, white walls, skin.

**Indoor Warm Light:** warm lamps, neutral objects, deep shadows, skin.

**Mixed Lighting:** daylight window + warm interior, mixed color temperatures.

**High Dynamic Range:** bright sky, dark foreground, reflective surfaces.

**Low Light:** high ISO noise, skin, colored lights, dark neutrals.

---

## 34. Style Acceptance Criteria

A valid style should:

```text
[ ] remain photographic
[ ] preserve believable neutral references
[ ] avoid broken skin color
[ ] preserve highlight roll-off
[ ] avoid shadow color pollution
[ ] preserve texture
[ ] avoid excessive sharpening
[ ] avoid excessive denoise
[ ] avoid HDR-like flattening
[ ] preview consistently with final output
[ ] return exactly to NATURAL at strength 0
```

---

## 35. Unit Tests

Add tests for:

```text
StylePoint clamping
center neutrality
strength interpolation
strength = 0 behavior
strength = 1 behavior
nonlinear XY mapping
Tone curve monotonicity
palette transform stability
neutral gray stability
skin-range hue stability
out-of-gamut handling
version migration
```

---

## 36. Golden Image Tests

Maintain reference fixtures for:

```text
Natural baseline
Warm style
Cool style
Gold palette
Rose palette
Muted color
Rich color
Soft tone
Deep tone
```

Compare with tolerance rather than exact equality when floating-point/GPU differences are expected.

---

## 37. Debug Tools

Debug builds should optionally expose:

```text
Tone X/Y
Color X/Y
Palette X/Y
Strength
resolved style parameters
style version
processing duration
```

Optional debug capture can save:

```text
01_natural_base
02_after_tone
03_after_color
04_after_palette
05_final
```

Never expose these as normal gallery output.

---

## 38. Performance Metrics

Measure:

```text
preview style latency
pad-drag update latency
full-resolution style processing time
peak memory
GPU/CPU cost
final encode latency
```

Do not optimize before establishing visually correct reference behavior.

---

## 39. CPU / GPU Strategy

Initial implementation should prioritize correctness.

Possible path:

```text
CPU reference implementation
         ↓
validated visual output
         ↓
GPU acceleration for preview
         ↓
GPU/full-res optimization if justified
```

Avoid creating different visual algorithms for preview and final capture unless unavoidable.

---

## 40. LUT Policy

LUT support is not required for Style v1.

Future LUT support may exist as an advanced feature, but built-in Style behavior should not depend entirely on opaque LUTs.

Reasons:

- harder to interpolate meaningfully;
- harder to preserve neutral behavior;
- harder to constrain extreme output;
- harder to reason about color correctness.

---

## 41. Suggested MVP

Style MVP includes:

```text
Style button
Style panel
Tone XY Pad
Color XY Pad
Palette XY Pad
Strength slider
Reset
live preview approximation
full-resolution final application
DataStore persistence
NATURAL integration
style versioning
basic presets
```

Excluded from Style MVP:

```text
user-imported LUTs
AI style generation
semantic masks
face-aware beautification
film grain system
vignette system
advanced local color grading
custom user preset sharing
cloud styles
```

---

## 42. Recommended Implementation Order

```text
01. Define StyleState and StylePoint
02. Implement StyleParameterResolver
03. Implement strength interpolation
04. Implement Tone processor
05. Implement Color processor
06. Implement Palette processor
07. Integrate after NATURAL base development
08. Add full-resolution tests
09. Add Style Pad Compose component
10. Connect UI to preview state
11. Implement preview approximation
12. Add reset behavior
13. Add persistence
14. Add initial presets
15. Add debug diagnostics
16. Benchmark preview
17. Validate real-device capture
```

---

## 43. First Milestone

> A StyleState can be applied to a known NATURAL image deterministically without camera UI.

Required:

```text
Tone works
Color works
Palette works
Strength works
center is neutral
strength zero equals NATURAL
unit tests pass
```

---

## 44. Second Milestone

> Style Pad modifies live preview responsively.

Required:

```text
smooth dragging
no camera restart
bounded memory
reasonable preview latency
reset works
```

---

## 45. Third Milestone

> Captured image closely matches selected preview style.

Validate:

```text
daylight
indoor
mixed light
skin
foliage
blue sky
high dynamic range
low light
```

---

## 46. Hard Rule: Style Is Not Correction

If NATURAL output is too blue, do not solve it by shifting the default Style coordinate toward warm.

If NATURAL output is too dark, do not solve it by making the default Tone style brighter.

Correct the NATURAL pipeline first.

The center Style state must remain truly neutral.

---

## 47. Hard Rule: Center Means Baseline

For every Style Pad:

```text
(0, 0)
```

means no creative deviation from NATURAL for that dimension.

This simplifies debugging, presets, interpolation, reset behavior, versioning, and user understanding.

---

## 48. Hard Rule: Style Is Parameter-Based

Primary architecture:

```text
XY input
 ↓
parameter resolver
 ↓
controlled image transformations
```

not:

```text
XY input
 ↓
random filter opacity
```

---

## 49. Definition of Done

Style v1 is complete when:

```text
[ ] NATURAL remains the technical baseline
[ ] Tone Pad works
[ ] Color Pad works
[ ] Palette Pad works
[ ] Strength works
[ ] Center state is neutral
[ ] Strength 0 equals NATURAL
[ ] Preview responds smoothly
[ ] Capture uses the same StyleState
[ ] Final rendering broadly matches preview
[ ] RAW remains unaffected
[ ] No beauty processing exists
[ ] No aggressive HDR is introduced
[ ] No arbitrary RGB correction is used as style foundation
[ ] Styles remain inside safe rendering bounds
[ ] Real-device scenes have been reviewed
[ ] Processing performance is measured
[ ] Style versioning exists
```

---

## 50. Final Rule

The Style system should make users feel that they are choosing **how the camera renders a photograph**, not applying a filter after the photograph already exists.

The correct product relationship is:

```text
Camera
  ↓
Technically correct NATURAL rendering
  ↓
Personal Style
  ↓
Photograph
```

not:

```text
Broken camera output
  ↓
filter
  ↓
compensation for pipeline problems
```

Style is a creative layer.

NATURAL remains the truth layer.
