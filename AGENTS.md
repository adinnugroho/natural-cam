# AGENTS.md

## 1. Authority

Before making any change, read:

1. `PRD.md`
2. `SPEC.md`
3. `AGENTS.md`

Authority order:

```text
PRD.md
  ↓
SPEC.md
  ↓
AGENTS.md
  ↓
Implementation
```

If documents conflict:

**PRD.md wins.**

`SPEC.md` explains how PRD requirements should be implemented.

`AGENTS.md` defines how agents must work on the project.

Never modify product intent merely because implementation is easier another way.

---

# 2. Product Mission

This project is an Android camera focused on:

> natural, restrained, immediately usable photography with less aggressive computational processing than typical OEM cameras where hardware access permits.

The product is not primarily:

* a manual camera
* a RAW viewer
* an HDR camera
* an AI camera
* a beauty camera
* a Lightroom replacement
* an OEM camera clone

The main user experience is:

```text
open camera
→ aim
→ press shutter
→ receive natural-looking photo
```

RAW is optional.

Natural finished output is the primary product.

---

# 3. Core Processing Philosophy

Always prefer:

* realistic texture
* restrained sharpening
* restrained denoising
* realistic color
* believable contrast
* smooth highlight roll-off
* natural shadows
* honest camera limitations

Avoid:

* aggressive HDR
* artificial clarity
* excessive shadow lifting
* excessive microcontrast
* strong saturation boosts
* beauty processing
* skin smoothing
* face reshaping
* detail hallucination
* artificial edge enhancement

Do not add processing simply because it makes a thumbnail look more impressive.

---

# 4. Processing Profiles

Public profiles are strictly:

```text
PURE
NATURAL
SYSTEM
```

Default:

```text
NATURAL
```

Do not introduce another public profile without updating `PRD.md`.

---

# 5. PURE

PURE means:

> the least processed practical result supported by the selected camera.

Preferred behavior where supported:

```text
HDR                 OFF
Noise reduction     OFF
Edge enhancement    OFF
Beauty              NONE
Effects             OFF
Local enhancement   NONE
Sharpening          OFF
```

If `OFF` is unavailable:

use the least aggressive supported mode.

Never claim:

```text
Noise Reduction: OFF
```

when the device cannot actually provide it.

Use honest capability descriptions.

---

# 6. NATURAL

NATURAL is the primary product mode.

Its goal is not zero processing.

Its goal is:

> enough processing to produce a finished photograph without creating the overprocessed smartphone look.

Optimize NATURAL for:

* preserved texture
* realistic skin
* believable noise
* restrained sharpening
* realistic white balance
* natural color
* gentle tone mapping
* controlled highlights

NATURAL should remain usable straight out of camera.

---

# 7. SYSTEM

SYSTEM represents conventional device/platform processing.

It may use:

* CameraX processed JPEG
* Camera2 JPEG
* OEM/platform ISP behavior

Do not try to make SYSTEM identical across manufacturers.

Do not treat SYSTEM output as the quality reference for NATURAL.

---

# 8. Profile Is Not Pipeline

Never hardcode assumptions such as:

```text
PURE = RAW
NATURAL = YUV
SYSTEM = CameraX
```

Profiles describe product intent.

Pipelines describe technical implementation.

One profile may use different pipelines on different devices.

Example:

```text
NATURAL
   ├── RAW pipeline
   ├── YUV pipeline
   └── processed fallback
```

---

# 9. Capability-First Development

Never assume Android cameras behave uniformly.

Before implementing a control:

```text
query capability
→ validate availability
→ select implementation
→ define fallback
```

Do not infer capabilities solely from:

* manufacturer
* phone model
* camera ID
* marketing lens name
* flagship status

Use actual camera metadata.

---

# 10. Physical Cameras

Treat each physical camera independently.

Never assume:

```text
main supports RAW
therefore ultrawide supports RAW
```

or:

```text
main supports manual focus
therefore telephoto supports manual focus
```

Capabilities belong to the selected camera.

---

# 11. Camera IDs

Never assume:

```text
0 = rear
1 = front
2 = ultrawide
```

Camera IDs may be arbitrary strings.

Always discover cameras through Android APIs.

---

# 12. Honest Hardware Behavior

The application must never pretend a requested camera setting succeeded when it did not.

Differentiate:

```text
requested
supported
confirmed
unknown
unavailable
```

If hardware silently ignores a setting on a known device:

document it as a compatibility limitation.

---

# 13. CameraX Policy

Prefer CameraX when it provides:

* stable lifecycle handling
* preview
* normal capture
* focus/metering
* exposure compensation
* RAW support
* lens selection

without blocking PRD requirements.

Do not abandon CameraX merely because Camera2 exposes more controls.

---

# 14. Camera2 Policy

Use Camera2 or Camera2 interoperability when required for:

* manual sensor control
* manual post-processing control
* lower-level CaptureRequest settings
* RAW/YUV configurations unavailable through current CameraX path
* verified physical-camera requirements
* documented compatibility workarounds

Do not introduce direct Camera2 complexity without a technical reason.

---

# 15. Camera Abstraction

UI and feature code must not directly manipulate:

```text
CameraDevice
CameraCaptureSession
CaptureRequest.Builder
CameraX UseCase
ImageReader
```

Use application/domain abstractions.

Required conceptual direction:

```text
UI
 ↓
ViewModel
 ↓
Use Case
 ↓
CameraController
 ↓
Camera backend
```

---

# 16. Image Pipeline Isolation

Image processing must remain independent from camera UI and camera lifecycle.

Correct:

```text
CaptureUseCase
 ↓
ImagePipeline
 ↓
Processing Stages
```

Forbidden:

```text
CameraScreen
 ↓
direct RAW processing
```

---

# 17. Processing Stages

Do not create one giant image-processing method.

Keep major operations independent.

Examples:

```text
RawNormalizer
WhiteBalancer
Demosaicer
CameraColorTransformer
ExposureProcessor
NaturalToneMapper
HighlightRollOff
NoiseReducer
Sharpener
GamutMapper
OutputTransformer
```

Each stage should have:

* explicit input
* explicit output
* clear numerical assumptions
* isolated tests where practical

---

# 18. RAW Rules

Never assume:

* RGGB Bayer
* fixed black level
* fixed white level
* fixed bit depth
* fixed color matrix
* fixed sensor orientation

Read metadata.

RAW pixel data without metadata is not sufficient for correct development.

---

# 19. CFA Handling

Do not hardcode RGGB.

Support camera-reported layouts such as:

```text
RGGB
GRBG
GBRG
BGGR
```

Unsupported sensor arrangements require explicit fallback or documented incompatibility.

---

# 20. RAW Numerical Precision

Do not convert RAW data to 8-bit early.

Preserve adequate numerical precision for:

* normalization
* white balance
* color conversion
* exposure
* tone mapping

Choose internal representation based on correctness first, performance second.

---

# 21. Color Management

Never treat sensor RGB as display RGB.

Required conceptual path:

```text
Sensor Space
 ↓
Camera Calibration
 ↓
Working Linear RGB
 ↓
Tone / Color Processing
 ↓
Output Color Space
```

Centralize matrix and color-space logic.

Do not scatter arbitrary color transforms across the codebase.

---

# 22. Gamma / Linear Processing

Operations based on physical light relationships should normally operate in linear space.

Do not apply algorithms blindly to gamma-encoded data.

If an algorithm intentionally uses non-linear data:

document the reason.

---

# 23. White Balance

Do not deliberately warm or cool every image for aesthetics.

Default white balance should aim for realism.

RAW white balance should use available capture metadata where practical.

Manual white balance must only expose values that correspond to real camera or pipeline behavior.

---

# 24. Tone Mapping

Default NATURAL tone mapping should be restrained.

Avoid:

* aggressive local HDR
* halo-producing local tone mapping
* extreme shadow recovery
* flattened highlights
* unnatural midtone compression

Prefer simple global tone behavior first.

Add complexity only when visual testing proves it necessary.

---

# 25. Highlight Handling

Prefer smooth highlight roll-off.

Avoid:

* abrupt clipping where preventable
* gray compressed highlights
* extreme highlight recovery that destroys natural contrast

Highlight processing must remain independently tunable.

---

# 26. Denoising

Noise is not automatically a defect.

NATURAL should preserve fine detail.

PURE may intentionally contain visible grain.

Avoid waxy rendering.

When tuning denoise, inspect:

* skin
* hair
* fabric
* foliage
* fine text
* shadow texture

Chroma noise may be treated more strongly than luminance noise when justified.

---

# 27. Sharpening

Sharpen conservatively.

Never optimize only for perceived sharpness.

Avoid:

* edge halos
* crunchy hair
* crunchy leaves
* exaggerated pores
* artificial microcontrast

PURE:

```text
OFF or minimum available
```

NATURAL:

```text
minimal / restrained
```

---

# 28. No Beauty Processing

Do not implement or silently introduce:

* skin smoothing
* face whitening
* face reshaping
* eye enlargement
* body reshaping
* portrait beautification
* semantic facial enhancement

This applies even when a dependency provides the feature automatically.

---

# 29. No Hallucinated Detail

Do not introduce algorithms that fabricate photographic detail by default.

Examples include:

* generative texture replacement
* AI facial reconstruction
* synthetic hair detail
* fake high-frequency enhancement

The camera should preserve reality, not reconstruct an idealized version.

---

# 30. Multi-Frame Processing

Multi-frame capture is not part of the MVP.

Do not implement it without an explicit task grounded in updated requirements.

Future multi-frame processing is acceptable only if it improves:

* noise
* dynamic range
* real detail

while preserving the natural rendering philosophy.

---

# 31. YUV Handling

When using `YUV_420_888`, never assume:

* packed planes
* identical strides
* fixed chroma arrangement

Respect:

* row stride
* pixel stride
* crop
* orientation

Incorrect stride handling is a correctness bug.

---

# 32. Processed JPEG

Do not pretend processed JPEG can be restored into RAW.

Avoid destructive "deprocessing" algorithms intended to reverse:

* sharpening
* denoising
* tone mapping

once information has already been destroyed.

When only processed JPEG exists:

use the safest honest fallback.

---

# 33. Capture Path Resolver

Capture path decisions must be centralized.

Do not spread logic such as:

```kotlin
if (rawSupported) { ... }
```

through UI and feature classes.

Use a resolver that considers:

```text
camera
capabilities
profile
RAW preference
available formats
performance constraints
known quirks
```

and produces a capture plan.

---

# 34. Fallback Rules

Fallbacks must preserve product intent.

Example:

```text
RAW unavailable
 ↓
YUV NATURAL
```

then:

```text
YUV control insufficient
 ↓
best available processed fallback
```

Do not silently use aggressive OEM processing while still labeling the result as PURE if the result no longer satisfies PURE semantics.

Record limitations.

---

# 35. UI Capability Rules

Only expose controls supported by the selected camera.

If unsupported:

prefer hiding or clearly disabling the control.

Do not create controls that appear functional but are ignored.

---

# 36. Default UI

Keep the primary UI simple.

Primary user workflow must remain:

```text
open
→ aim
→ shutter
```

Advanced technical controls must not dominate the default view.

---

# 37. Pro Controls

Future Pro mode may contain:

* ISO
* shutter
* focus
* white balance
* histogram
* focus peaking
* clipping warnings

Do not add these simply because APIs exist.

Implement only when required by PRD/SPEC/task.

---

# 38. Compose Rules

Composables should remain declarative.

Do not:

* own camera sessions in Composables
* process RAW in Composables
* perform storage writes directly from UI
* keep application-wide mutable state in Composables

Use ViewModels and use cases.

---

# 39. State Management

Prefer immutable explicit state.

Example:

```kotlin
data class CameraUiState(
    val cameraState: CameraState,
    val captureState: CaptureState,
    val selectedCamera: CameraId?,
    val selectedProfile: ProcessingProfile,
    val rawMode: RawMode,
    val exposureCompensation: Float
)
```

Avoid hidden mutable singleton state.

---

# 40. Capture State

Capture must be modeled explicitly.

Conceptually:

```text
IDLE
 ↓
PREPARING
 ↓
CAPTURING
 ↓
PROCESSING
 ↓
SAVING
 ↓
COMPLETE
 ↓
IDLE
```

A failure must transition into a recoverable state.

Do not leave the shutter permanently disabled after errors.

---

# 41. Shutter Responsiveness

User feedback should happen immediately after shutter input.

Do not wait for:

* RAW development
* JPEG encoding
* MediaStore write

before providing visual shutter acknowledgement.

Actual camera safety still takes priority.

---

# 42. Backpressure

Never allow unlimited full-resolution processing jobs.

If processing is slower than capture:

use bounded queues or temporarily limit new captures.

Never acknowledge a capture and then silently discard it.

---

# 43. Threads

Never perform heavy work on:

```text
main thread
Compose thread
camera callback thread
```

Heavy work includes:

* RAW development
* large YUV conversion
* demosaic
* denoise
* sharpening
* full-resolution encoding

Use appropriate coroutine dispatchers/executors.

---

# 44. Image Lifetime

Every acquired camera image must have explicit ownership.

Always close/release images.

A forgotten `Image.close()` is a critical camera bug.

Do not retain frames longer than needed.

---

# 45. Memory

Full-resolution images can consume significant memory.

Avoid:

* repeated full-resolution copies
* unnecessary bitmap conversions
* multiple decoded versions of the same capture
* unbounded processing queues

Benchmark memory on mid-range phones.

---

# 46. Performance

Optimize only after measurement.

Measure at minimum:

```text
camera startup
preview startup
shutter latency
capture latency
processing latency
encoding latency
save latency
peak memory
```

Do not compromise image correctness merely to improve a benchmark.

---

# 47. NDK

Do not introduce C/C++ by default.

NDK requires:

* demonstrated bottleneck
* benchmark
* clear performance benefit
* maintenance justification
* tests

Start with maintainable Kotlin/platform implementations.

---

# 48. GPU

Do not make custom GPU processing mandatory before CPU/reference correctness exists.

GPU acceleration may later target:

* demosaic
* color transforms
* tone mapping
* denoise
* sharpening

Keep a clear reference implementation where feasible.

---

# 49. Dependencies

Before adding any dependency, verify:

1. Why is it necessary?
2. Can Android APIs already do this?
3. Is the project maintained?
4. What is the binary-size cost?
5. Does it add network access?
6. Does it add analytics?
7. Does it alter image output?
8. Is its license acceptable?

Avoid dependency bloat.

---

# 50. Storage

User-visible photographs must use standard Android media storage.

Use MediaStore where required by SPEC.

Do not build a proprietary photo library as a replacement for system gallery storage.

---

# 51. Temporary Files

Temporary assets must be removed after:

* successful processing
* failed processing
* interrupted stale jobs where safe

Do not allow RAW/YUV temporary files to accumulate indefinitely.

---

# 52. Metadata

Preserve correct metadata when available.

Never invent:

* ISO
* shutter
* aperture
* lens
* GPS
* camera model values

If data is unavailable:

omit it.

---

# 53. Location

Location tagging defaults to disabled.

Do not require location permission for camera usage.

Do not access precise location unless the user has enabled photo geotagging and granted permission.

---

# 54. Privacy

Captured images stay on device unless the user intentionally shares them.

Do not:

* upload photos
* upload RAW
* log image contents
* send EXIF GPS to analytics
* require cloud processing

Core camera features must work offline.

---

# 55. Logging

Debug logs may include:

```text
capture ID
camera ID
capabilities
capture plan
profile
source format
pipeline version
processing timings
errors
```

Never log:

* raw pixel values
* entire image buffers
* exact GPS data
* private media contents

---

# 56. Typed Errors

Do not swallow camera errors.

Convert implementation errors into typed domain errors.

Bad:

```kotlin
try {
    ...
} catch (_: Exception) {
}
```

Preferred:

```text
catch error
 ↓
log technical cause
 ↓
map to domain error
 ↓
restore safe state
 ↓
inform UI if needed
```

---

# 57. Camera Recovery

Camera recovery attempts must be controlled.

On recoverable failure:

```text
close invalid session
 ↓
re-open camera
 ↓
restore compatible configuration
```

Avoid infinite retry loops.

---

# 58. Vendor Quirks

Prefer capability-driven behavior.

Vendor-specific workarounds belong in:

```text
compat/
```

Do not scatter:

```kotlin
if (Build.MANUFACTURER == ...)
```

through unrelated modules.

A device workaround requires:

* reproducible issue
* affected device/build
* technical explanation
* isolated implementation
* documentation
* test where practical

---

# 59. Real Device Testing

Camera functionality is not complete because it works on an emulator.

Real-device validation is mandatory for:

* RAW
* Camera2 controls
* focus
* exposure
* physical cameras
* ISP modes
* image quality
* lens switching

---

# 60. Device Diversity

When available, test across different vendor families.

Examples:

```text
Google Pixel
Samsung Galaxy
Xiaomi / Redmi
Oppo / OnePlus / Realme
```

The purpose is detecting fragmentation, not creating vendor-specific visual presets.

---

# 61. Image Quality Testing

For NATURAL and PURE, evaluate real scenes including:

* skin
* daylight
* foliage
* fine text
* indoor lighting
* mixed lighting
* high dynamic range
* shadows
* high ISO
* saturated objects
* bright highlights

Review full-resolution crops.

Do not judge only thumbnails.

---

# 62. Image Regression Tests

For deterministic processing stages, maintain reference fixtures.

Verify:

* valid channel ranges
* no NaN/Infinity
* matrix correctness
* stride handling
* CFA mapping
* black-level correction
* white-level normalization
* orientation
* color transforms

---

# 63. Processing Changes

Any code change affecting appearance must document:

```text
what changed

why

which profile

expected effect

possible downside

test devices

comparison result
```

Do not make unexplained visual changes.

---

# 64. Magic Numbers

Avoid unexplained constants such as:

```kotlin
contrast = 1.18f
saturation = 1.07f
sharpen = 0.42f
```

If tuning values are empirical:

document why they exist and where they were evaluated.

Centralize tunable parameters.

---

# 65. Pipeline Versioning

Image pipelines must have internal versions.

Example:

```text
pure-v1
natural-v1
natural-v2
```

When image behavior changes materially:

increment the relevant pipeline version.

This is important for debugging regressions.

---

# 66. Experimental Processing

Experimental image processing must stay behind internal flags.

Examples:

```text
gpu_demosaic
natural_tone_v2
new_color_matrix
advanced_denoise
heif_output
```

Do not silently expose experimental output to release users.

---

# 67. Build Types

Debug builds may expose:

* camera metadata
* capability details
* capture source
* pipeline version
* timings
* experimental flags

Release builds should contain only user-relevant controls.

---

# 68. Architecture Changes

Do not perform broad refactors purely because another design looks cleaner.

Before a major architecture change, determine:

```text
actual problem
affected PRD requirement
affected SPEC section
migration cost
camera-session risk
testing impact
```

Prefer incremental improvement.

---

# 69. Documentation Changes

If implementation reveals a platform limitation:

do not alter `PRD.md` just to make implementation appear compliant.

Correct approach:

```text
PRD intent
 ↓
technical limitation
 ↓
SPEC fallback
 ↓
implementation
```

Only change PRD when the product decision itself changes.

---

# 70. PRD Traceability

Every major feature must map to an existing PRD requirement.

Before implementing a feature, ask:

```text
Which PRD requirement does this satisfy?
```

If none:

do not assume it belongs in the product.

---

# 71. SPEC Traceability

Before implementing a camera or image-processing feature:

identify the corresponding SPEC sections.

If SPEC lacks enough detail:

extend SPEC without contradicting PRD before inventing ad-hoc architecture.

---

# 72. Implementation Workflow

For each feature:

```text
1. Read related PRD sections.

2. Read related SPEC sections.

3. Inspect current architecture.

4. Identify required camera capabilities.

5. Define supported behavior.

6. Define unsupported behavior.

7. Define fallback.

8. Implement domain contract.

9. Implement backend/pipeline.

10. Implement UI if required.

11. Add tests.

12. Test real hardware when relevant.

13. Record limitations.
```

---

# 73. MVP Priority

Follow the implementation sequence defined in SPEC.

Broad priority:

```text
stable camera
 ↓
correct capability detection
 ↓
reliable JPEG
 ↓
RAW
 ↓
PURE
 ↓
NATURAL
 ↓
optimization
```

Do not start by building advanced AI/image effects.

---

# 74. Milestone Discipline

First milestone:

> reliable camera foundation.

Second milestone:

> demonstrably less processed PURE output.

Third milestone:

> usable NATURAL output.

Do not optimize milestone three before milestone one is stable.

---

# 75. Do Not Overengineer

Do not create abstractions for hypothetical future requirements unless they clearly reduce current complexity.

Prefer:

* simple contracts
* explicit state
* small components

Avoid:

* unnecessary generic frameworks
* giant inheritance trees
* service locators
* large manager classes
* speculative plugin systems

---

# 76. Code Style

Prefer:

* idiomatic Kotlin
* immutable state
* sealed types
* meaningful names
* small functions
* explicit error handling
* structured concurrency

Avoid:

* god classes
* hidden globals
* mutable singleton state
* unexplained reflection
* broad exception swallowing

---

# 77. Naming

Names should describe intent.

Good:

```text
CameraCapabilityScanner
CapturePathResolver
RawImagePipeline
NaturalToneMapper
CaptureCoordinator
```

Poor:

```text
CameraUtils
ImageHelper
ProcessorManager
Stuff
Common2
```

---

# 78. Comments

Comments should explain:

* why
* camera quirks
* image-processing assumptions
* synchronization requirements
* non-obvious mathematical behavior

Do not comment obvious code.

---

# 79. Commit / Task Scope

Keep implementation changes focused.

Avoid combining unrelated work such as:

```text
camera architecture refactor
+
new tone mapper
+
UI redesign
+
dependency upgrade
```

unless technically inseparable.

Focused changes make camera regressions easier to diagnose.

---

# 80. Definition of Done — Camera Feature

Before marking a camera feature complete:

```text
[ ] Matches PRD

[ ] Matches SPEC

[ ] Capability detection exists

[ ] Supported case works

[ ] Unsupported case works

[ ] Fallback is honest

[ ] Errors handled

[ ] Lifecycle handled

[ ] No Image leaks

[ ] UI state remains correct

[ ] Real device tested when relevant

[ ] Limitations documented
```

---

# 81. Definition of Done — Image Processing

Before marking processing work complete:

```text
[ ] Correct profile identified

[ ] Input format understood

[ ] Input/output range documented

[ ] Algorithm documented

[ ] Color space documented

[ ] No unexplained magic constants

[ ] Numerical tests added

[ ] Real images inspected

[ ] Skin inspected

[ ] Texture inspected

[ ] Highlight behavior inspected

[ ] Noise inspected

[ ] Edge halos inspected

[ ] Performance measured

[ ] Memory measured

[ ] No accidental beautification

[ ] No aggressive hidden HDR

[ ] No aggressive sharpening

[ ] No excessive denoising
```

---

# 82. Forbidden Shortcuts

Never solve a product requirement by:

* falsely reporting hardware support;
* silently switching PURE to OEM output;
* adding aggressive image processing;
* hiding camera errors;
* hardcoding one phone model's behavior globally;
* sending photos to cloud processing;
* requiring RAW for normal usage;
* putting Camera2 code directly in Compose;
* retaining frames indefinitely;
* rewriting PRD to match implementation limitations.

---

# 83. Decision Rule

When choosing between:

```text
visually dramatic output
```

and:

```text
natural photographic output
```

follow PRD.

When choosing between:

```text
complex but theoretically powerful implementation
```

and:

```text
simpler reliable implementation that satisfies PRD
```

prefer reliability.

When choosing between:

```text
pretending a device supports a feature
```

and:

```text
showing a limitation
```

show the limitation.

---

# 84. Final Agent Instruction

Do not build:

> another Android camera with as many features as possible.

Do not build:

> a RAW-only camera that requires editing every photograph.

Do not build:

> an OEM camera clone with different UI.

Build:

> a reliable Android camera whose architecture deliberately minimizes unnecessary computational photography and produces restrained, natural, immediately usable photographs while honestly adapting to the capabilities of each device.

Every implementation decision should support that objective.
