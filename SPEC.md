# SPEC.md

## 1. Document Authority

This document defines the technical implementation of the product described in `PRD.md`.

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

`PRD.md` is the product source of truth.

If this specification conflicts with the PRD:

**PRD wins.**

If Android hardware or platform limitations prevent an exact implementation of a PRD requirement:

1. preserve the original product intent;
2. document the limitation;
3. implement the closest technically honest fallback;
4. never silently redefine the product requirement.

---

# 2. Technical Goal

Build an Android camera application whose primary function is to produce:

> natural-looking, immediately usable photographs with significantly less aggressive computational processing than typical OEM camera applications where hardware access permits.

The implementation must support three processing philosophies:

```text
PURE
NATURAL
SYSTEM
```

The architecture must allow these profiles to use different capture and processing paths while sharing the same camera UI and storage infrastructure.

RAW is an important capability but is not the product itself.

---

# 3. Core Technical Principles

The architecture must optimize for:

1. camera reliability;
2. honest device capability detection;
3. natural image rendering;
4. predictable output;
5. modular image processing;
6. low UI complexity;
7. hardware-aware fallback behavior;
8. maintainability across Android vendors.

The architecture must not optimize primarily for:

* maximum feature count;
* maximum HDR;
* maximum perceived sharpness;
* maximum denoising;
* benchmark scores;
* vendor-specific visual enhancement.

---

# 4. Technology Stack

Primary implementation:

```text
Language        Kotlin
UI              Jetpack Compose
Concurrency     Kotlin Coroutines / Flow
Camera          CameraX + Camera2 interoperability
Low-level       Camera2 when required
Preferences     DataStore
Storage         MediaStore
RAW Container   DNG
Primary Output  JPEG
```

CameraX should be preferred for lifecycle-safe camera orchestration where it does not prevent PRD requirements.

Camera2 and Camera2 interoperability should be used when low-level sensor or post-processing control is required.

Direct Camera2 implementation is allowed when CameraX cannot satisfy a required capture path.

The remainder of the application must not depend directly on which backend is selected.

---

# 5. Minimum Platform Strategy

The application should not choose the lowest possible Android API level merely for installation reach.

The minimum supported Android version should be chosen based on:

* stable Camera2 behavior;
* CameraX requirements;
* supported Compose version;
* MediaStore behavior;
* RAW/DNG requirements;
* device test coverage.

Legacy Android support must not compromise camera correctness.

Exact `minSdk`, `targetSdk`, and dependency versions are implementation-time decisions and must be documented in the build configuration.

---

# 6. High-Level Architecture

```text
┌──────────────────────────────┐
│           UI Layer           │
│ Compose / ViewModels         │
└──────────────┬───────────────┘
               │
┌──────────────▼───────────────┐
│       Application Layer      │
│ Capture / Focus / Exposure   │
│ Profile / Settings UseCases  │
└──────────────┬───────────────┘
               │
┌──────────────▼───────────────┐
│         Camera Domain        │
│ CameraController             │
│ CapabilityRepository         │
│ CaptureCoordinator           │
└───────┬───────────┬──────────┘
        │           │
        │           └────────────────┐
        ▼                            ▼
┌───────────────┐           ┌────────────────┐
│ Camera Backend│           │ Image Pipeline │
│ CameraX/Cam2  │           │ RAW / YUV      │
└───────┬───────┘           └────────┬───────┘
        │                            │
        ▼                            ▼
 Android Camera HAL           Encoder / Metadata
                                      │
                                      ▼
                                  MediaStore
```

The camera backend and image processing engine must remain separate.

---

# 7. Logical Modules

Recommended logical boundaries:

```text
app

camera-domain
camera-backend
camera-capabilities

capture
exposure
focus

image-core
image-raw
image-yuv
image-processing
image-encoding

metadata
storage

settings

ui-camera
ui-settings
ui-device-info

compat
common
```

These do not have to become separate Gradle modules immediately.

MVP may begin with fewer physical modules as long as architectural boundaries remain explicit.

Avoid premature modularization that makes iteration slower.

---

# 8. Camera Backend Abstraction

The application layer must interact with a camera abstraction rather than CameraX or Camera2 directly.

Conceptual interface:

```kotlin
interface CameraController {

    val state: StateFlow<CameraState>

    suspend fun initialize()

    suspend fun selectCamera(
        cameraId: CameraId
    )

    suspend fun configure(
        configuration: CameraConfiguration
    )

    suspend fun focusAt(
        point: NormalizedPoint
    )

    suspend fun lockFocus()

    suspend fun unlockFocus()

    suspend fun setExposureCompensation(
        ev: Float
    )

    suspend fun capture(
        request: PhotoCaptureRequest
    ): PhotoCaptureResult

    suspend fun close()
}
```

Exact implementation may differ.

UI code must never manipulate `CameraDevice`, CameraX use cases, or `CaptureRequest.Builder` directly.

---

# 9. Camera Backends

The architecture should allow multiple implementations.

```text
CameraController
      │
      ├── CameraXBackend
      │
      └── Camera2Backend
```

## 9.1 CameraXBackend

Preferred for:

* camera lifecycle;
* preview;
* standard JPEG capture;
* compatible RAW capture;
* focus/metering;
* common camera selection;
* flash;
* basic exposure compensation.

Modern CameraX exposes RAW and RAW+JPEG output capabilities on supported cameras, so this path should be evaluated before creating a parallel Camera2 implementation.

## 9.2 Camera2Backend

Use when required for:

* direct capture-request manipulation;
* fine-grained sensor controls;
* post-processing controls;
* RAW/YUV stream configurations not sufficiently exposed by CameraX;
* physical-camera behavior unavailable through the chosen CameraX path;
* verified device compatibility workarounds.

Direct Camera2 should not be used merely because it offers more knobs.

---

# 10. Camera Capability Discovery

Capabilities must be discovered per camera.

Do not determine functionality from:

```text
manufacturer name
device marketing name
camera ID number
lens label
```

Query the camera framework.

The capability scanner should inspect at minimum:

```text
hardware support level

available request capabilities

RAW support

manual sensor support

manual post-processing support

read-sensor-settings support

logical multi-camera support

physical cameras

supported stream formats

supported resolutions

noise-reduction modes

edge-enhancement modes

AE modes

AF modes

AWB modes

exposure compensation

sensor sensitivity range

sensor exposure-time range

minimum focus distance

focal lengths

flash availability

optical stabilization

video stabilization

sensor orientation

color-filter arrangement

color calibration metadata

dynamic range capabilities

color-space capabilities
```

---

# 11. CameraCapabilities Model

Example domain representation:

```kotlin
data class CameraCapabilities(
    val cameraId: CameraId,

    val lensFacing: LensFacing,

    val physicalCameraIds: Set<CameraId>,

    val hardwareLevel: HardwareLevel,

    val rawSupported: Boolean,

    val manualSensorSupported: Boolean,

    val manualPostProcessingSupported: Boolean,

    val sensorSettingsReadable: Boolean,

    val logicalMultiCamera: Boolean,

    val noiseReductionModes: Set<NoiseReductionMode>,

    val edgeModes: Set<EdgeMode>,

    val supportedFormats: Set<CaptureFormat>,

    val resolutions: Map<CaptureFormat, List<ImageSize>>,

    val isoRange: IntRange?,

    val exposureTimeRange: ClosedRange<Long>?,

    val exposureCompensationRange: ClosedFloatingPointRange<Float>?,

    val minimumFocusDistance: Float?,

    val focalLengthsMm: List<Float>,

    val flashAvailable: Boolean,

    val opticalStabilizationSupported: Boolean,

    val sensorOrientation: Int
)
```

Android framework objects should not leak into the UI/domain model unless necessary.

---

# 12. Capability Truth Model

The application must differentiate between:

```text
REQUESTED
SUPPORTED
CONFIRMED
UNAVAILABLE
UNKNOWN
```

This is important because Android camera APIs may advertise a control that behaves inconsistently on particular hardware.

Example:

```kotlin
enum class CapabilityConfidence {
    SUPPORTED,
    CONFIRMED,
    UNAVAILABLE,
    UNKNOWN
}
```

Device quirks discovered through testing may update application compatibility knowledge, but they must not replace capability discovery entirely.

---

# 13. Capability Persistence

Camera capability discovery may be cached locally.

Cache key should include enough information to invalidate stale results, such as:

```text
device identity
Android build
application version
camera ID
```

Re-scan if:

* Android version changes;
* application capability logic changes;
* camera enumeration changes;
* cached data becomes incompatible.

---

# 14. Capability-Based UI

UI controls must be derived from the selected camera's capabilities.

Example:

If RAW is unavailable:

```text
RAW toggle → hidden or disabled
```

If manual focus is unavailable:

```text
manual focus → hidden
```

If exposure compensation is unsupported:

```text
EV control → hidden
```

The application must not present fictional controls.

---

# 15. Camera Selection

The application must distinguish between:

```text
logical camera
physical camera
user-facing lens option
```

These are not necessarily identical.

A user might see:

```text
0.6×
1×
3×
```

but internally the implementation may be using:

* separate physical camera IDs;
* one logical multi-camera;
* focal-length switching managed by the HAL.

Lens UI must be built from actual camera capabilities.

---

# 16. CameraId

Use a domain-specific identifier.

```kotlin
@JvmInline
value class CameraId(
    val value: String
)
```

Do not assume camera IDs are numeric.

---

# 17. Camera State

Camera state must be explicit.

```kotlin
sealed interface CameraState {

    data object Uninitialized : CameraState

    data object Initializing : CameraState

    data class Ready(
        val cameraId: CameraId
    ) : CameraState

    data class Switching(
        val from: CameraId,
        val to: CameraId
    ) : CameraState

    data class Error(
        val error: CameraError
    ) : CameraState
}
```

Unexpected backend errors must never leave UI believing the camera is ready.

---

# 18. Processing Profiles

Public processing profiles are:

```kotlin
enum class ProcessingProfile {
    PURE,
    NATURAL,
    SYSTEM
}
```

No other public profile should be introduced without a PRD update.

Default:

```text
NATURAL
```

---

# 19. Profile vs Pipeline

A processing profile is a product intent.

A pipeline is an implementation.

They are deliberately separate.

Example:

```text
NATURAL
   │
   ├── RAW Natural Pipeline
   │
   ├── YUV Natural Pipeline
   │
   └── Processed-input Natural Pipeline
```

Do not define:

```text
NATURAL = RAW
```

or:

```text
PURE = Camera2
```

The application chooses the best available technical path for the requested profile.

---

# 20. Capture Path Selection

Before capture, resolve:

```text
Selected camera
      ↓
Capabilities
      ↓
Requested profile
      ↓
Requested output
      ↓
Available streams
      ↓
Capture Path Resolver
      ↓
Concrete Capture Plan
```

Example:

```kotlin
data class CapturePlan(
    val source: CaptureSource,
    val finalPipeline: PipelineType,
    val saveRaw: Boolean,
    val ispConfiguration: IspConfiguration,
    val limitations: Set<CaptureLimitation>
)
```

---

# 21. CaptureSource

```kotlin
enum class CaptureSource {
    RAW_SENSOR,
    YUV,
    PROCESSED_JPEG
}
```

Additional formats may be introduced later.

---

# 22. Capture Path Priority

For custom NATURAL processing, preference should generally be:

```text
RAW_SENSOR
    ↓
YUV
    ↓
Processed JPEG
```

However, RAW must not automatically be selected when doing so causes unacceptable:

* capture latency;
* memory pressure;
* device instability;
* unsupported lens behavior;
* image pipeline quality regression.

The resolver may use another path when objectively safer.

---

# 23. PURE Definition

PURE means:

> the least processed practical output the application can obtain from the selected camera while still producing a usable photograph.

Preferred controls:

```text
noise reduction     OFF
edge enhancement    OFF
effects             OFF
beautification      none
HDR stacking        disabled when controllable
local enhancement   none
tone processing     minimum required
```

When `OFF` is unsupported:

```text
use the least aggressive supported option
```

For example:

```text
OFF
→ MINIMAL
→ least aggressive supported mode
```

The UI must not claim processing is disabled when it is not.

---

# 24. PURE Output Strategies

Possible PURE strategies:

### Strategy A

```text
RAW
 ↓
minimal custom development
 ↓
final JPEG
```

Preferred long-term implementation.

### Strategy B

```text
YUV
 ↓
minimal transformation
 ↓
JPEG
```

Useful where RAW is unavailable or impractical.

### Strategy C

```text
least-processed hardware output
 ↓
JPEG
```

Fallback.

The selected strategy must be exposed internally for diagnostics.

---

# 25. NATURAL Definition

NATURAL is the primary user experience.

NATURAL must produce a finished image while maintaining:

```text
natural texture
natural color
controlled highlights
believable shadows
restrained noise reduction
restrained sharpening
gentle contrast
```

NATURAL does not mean:

```text
flat RAW preview
```

The user should be able to use the result immediately.

---

# 26. NATURAL Pipeline

Target pipeline:

```text
Capture Source

      ↓

Input decoding

      ↓

Sensor / input normalization

      ↓

White balance

      ↓

Demosaic
[RAW only]

      ↓

Camera color transform

      ↓

Exposure normalization

      ↓

Global tone mapping

      ↓

Highlight roll-off

      ↓

Optional restrained denoise

      ↓

Optional restrained sharpening

      ↓

Gamut mapping

      ↓

Output transfer function

      ↓

JPEG encoding

      ↓

Metadata

      ↓

MediaStore
```

Each stage must be replaceable independently.

---

# 27. SYSTEM Definition

SYSTEM represents the conventional processed output path.

It may use:

```text
CameraX JPEG
Camera2 JPEG
OEM/platform image processing
```

The application should preserve SYSTEM output as much as practical.

Do not run the NATURAL pipeline over SYSTEM unless specifically required by an output operation.

SYSTEM exists primarily for:

* comparison;
* compatibility;
* reference;
* fallback where explicitly communicated.

---

# 28. RAW Capture

RAW support must be capability-driven.

Where supported, the application should allow:

```text
Final only
RAW + Final
RAW only
```

Domain representation:

```kotlin
enum class RawMode {
    OFF,
    RAW_PLUS_FINAL,
    RAW_ONLY
}
```

RAW output format:

```text
DNG
```

RAW data must never be represented to the user as unprocessed sensor data if device-specific processing or remosaic behavior applies.

---

# 29. CameraX RAW

When CameraX reports support for RAW output, the CameraX path may be used for:

```text
RAW
RAW + JPEG
```

The implementation must still verify that the selected camera reports the required output format.

Unsupported formats must be discovered before capture rather than failing after the shutter when possible.

---

# 30. Camera2 RAW

Direct Camera2 RAW capture may be used where:

* CameraX does not expose necessary behavior;
* custom RAW processing requires stream-level access;
* simultaneous stream configuration requires Camera2;
* testing demonstrates a better and more reliable implementation.

RAW capture should use the metadata associated with the exact captured frame.

---

# 31. RAW Metadata

RAW processing requires capture and static metadata including, where available:

```text
CFA arrangement

black level

white level

neutral color point

color transform

forward matrix

calibration transform

lens shading information

exposure time

ISO

sensor crop

active array

orientation

focal length
```

Do not assume fixed sensor constants.

---

# 32. RAW Representation

Internal RAW representation should explicitly contain metadata.

Example:

```kotlin
data class RawFrame(
    val width: Int,
    val height: Int,

    val pixels: RawBuffer,

    val colorFilterArrangement: ColorFilterArrangement,

    val blackLevel: BlackLevel,

    val whiteLevel: Int,

    val metadata: RawMetadata
)
```

Never pass a bare byte array through the pipeline without associated interpretation metadata.

---

# 33. RAW Precision

RAW intermediate processing should avoid premature conversion to 8-bit.

Use sufficient numerical precision for:

* normalization;
* white balance;
* color transforms;
* tone operations.

Exact internal numeric representation should be selected based on benchmarks.

Candidates:

```text
16-bit integer
16-bit float
32-bit float
```

Correctness takes priority during the first implementation.

---

# 34. Demosaicing

The RAW pipeline must treat demosaicing as a modular stage.

```kotlin
interface Demosaicer {

    fun demosaic(
        frame: RawFrame,
        context: ProcessingContext
    ): LinearRgbImage
}
```

Initial implementation may favor:

```text
correctness
simplicity
predictability
```

over computationally expensive state-of-the-art algorithms.

More advanced algorithms may be introduced later.

---

# 35. Bayer / CFA Handling

Never assume RGGB.

The implementation must support the sensor's reported color-filter arrangement.

Potential layouts include:

```text
RGGB
GRBG
GBRG
BGGR
```

Non-standard or unsupported sensor arrangements require explicit handling or fallback.

---

# 36. Black-Level Correction

RAW normalization must account for black-level metadata.

Conceptually:

```text
normalized =
(raw - blackLevel)
/
(whiteLevel - blackLevel)
```

Values must be clamped appropriately.

Per-channel or pattern-dependent black levels must be supported where required.

---

# 37. White Balance

RAW white balance should derive from capture metadata where practical.

Pipeline:

```text
sensor RGB
 ↓
neutral point / WB coefficients
 ↓
balanced sensor RGB
```

NATURAL may apply restrained correction after the sensor-derived white balance if required.

Avoid deliberately warming every image.

---

# 38. Color Transformation

RAW sensor values must not be treated directly as display RGB.

Conceptual path:

```text
Camera/Sensor Space
      ↓
Camera Calibration
      ↓
Working Linear RGB
      ↓
Output Color Space
```

Color matrix handling must be centralized.

Do not scatter matrix transformations across pipeline code.

---

# 39. Working Color Space

The pipeline must define an internal working color representation.

MVP may use a practical wide linear RGB working space internally.

Requirements:

* known primaries;
* known white point;
* linear transfer during physical image operations;
* clear conversion into final output space.

Do not perform tone operations on gamma-encoded values unless the algorithm intentionally requires it.

---

# 40. Output Color

MVP JPEG output should prioritize broad Android compatibility.

Default final output:

```text
SDR JPEG
standard broadly compatible color space
```

Wide-gamut or HDR output can be introduced later.

The initial NATURAL profile should not depend on HDR displays.

---

# 41. YUV Pipeline

When RAW is unavailable or unsuitable:

```text
YUV_420_888
      ↓
YUV decode
      ↓
working RGB
      ↓
white/color adjustment
      ↓
tone
      ↓
optional denoise
      ↓
optional sharpening
      ↓
JPEG
```

The implementation must correctly respect:

* row stride;
* pixel stride;
* chroma layout;
* crop region;
* orientation.

Do not assume tightly packed planes.

---

# 42. Processed JPEG Input

When only processed JPEG is available:

```text
JPEG
 ↓
decode only if modification required
 ↓
minimal correction
 ↓
encode
```

Avoid unnecessary decode/re-encode operations.

The application must not attempt aggressive "deprocessing" designed to reconstruct information already destroyed by ISP sharpening or denoising.

---

# 43. Tone Mapping

NATURAL tone mapping must be primarily global and restrained.

Desired behavior:

```text
preserve midtone realism
protect important highlights
avoid crushed shadows
avoid unnatural shadow lift
avoid HDR-like halos
```

Initial implementation should avoid sophisticated local tone mapping.

Local tone mapping may be investigated later only if it preserves the PRD philosophy.

---

# 44. Highlight Roll-Off

Highlights should transition smoothly toward clipping.

Avoid:

```text
hard digital clipping where preventable
gray-looking compressed highlights
extreme highlight flattening
```

Highlight behavior should be tunable independently from global contrast.

---

# 45. Exposure Normalization

The image pipeline may apply small exposure normalization.

This is not permission to compensate for fundamentally incorrect capture exposure.

Exposure correction should have strict limits.

Example conceptual limit:

```text
small photographic correction
not multi-stop rescue by default
```

Large correction should remain a future explicit feature rather than hidden default behavior.

---

# 46. Denoising

Denoising is optional and profile dependent.

Logical strengths:

```kotlin
enum class DenoiseStrength {
    OFF,
    MINIMAL,
    NATURAL
}
```

PURE:

```text
OFF or minimum available
```

NATURAL:

```text
restrained
```

The target is:

```text
retain texture
while suppressing distracting noise
```

Not:

```text
erase all visible noise
```

---

# 47. Denoising Philosophy

Fine luminance grain may remain.

Avoid destroying:

* skin texture;
* hair strands;
* fabric;
* foliage;
* fine surfaces.

Chroma noise may be treated somewhat more strongly than luminance noise if required.

Any noise model should be parameterized rather than hidden inside arbitrary constants.

---

# 48. Sharpening

Sharpening must be a distinct processing stage.

```kotlin
enum class SharpeningStrength {
    OFF,
    MINIMAL,
    NATURAL
}
```

PURE:

```text
OFF
```

where possible.

NATURAL:

```text
minimal to restrained
```

Avoid:

* white halos;
* black halos;
* exaggerated pores;
* crunchy foliage;
* excessive microcontrast.

---

# 49. Scaling

Image scaling must have an explicit place in the pipeline.

Do not resize multiple times.

Preferred:

```text
process at required resolution
 ↓
single final scaling operation if necessary
 ↓
final sharpening
 ↓
encode
```

The ordering may change if benchmarks and image-quality tests demonstrate a better result.

---

# 50. ImagePipeline Interface

Suggested interface:

```kotlin
interface ImagePipeline {

    suspend fun process(
        input: ImageInput,
        profile: ProcessingProfile,
        metadata: CaptureMetadata,
        configuration: ProcessingConfiguration
    ): ProcessedImage
}
```

Possible implementations:

```text
RawImagePipeline
YuvImagePipeline
SystemImagePipeline
```

---

# 51. Processing Stages

Processing stages should have small contracts.

Example:

```kotlin
fun interface ImageStage<I, O> {

    suspend fun process(
        input: I,
        context: ProcessingContext
    ): O
}
```

Potential stages:

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

---

# 52. ProcessingConfiguration

Processing values should be explicit.

Example:

```kotlin
data class ProcessingConfiguration(
    val denoiseStrength: DenoiseStrength,
    val sharpeningStrength: SharpeningStrength,
    val toneProfile: ToneProfile,
    val targetColorSpace: OutputColorSpace,
    val quality: Int
)
```

Avoid hidden mutable global processing configuration.

---

# 53. Processing Version

Every processing pipeline release should have an internal version.

Example:

```text
natural-v1
natural-v2
natural-v3
natural-v4
natural-v5
natural-v6
pure-v1
pure-v2
```

This assists:

* regression diagnosis;
* image comparisons;
* compatibility testing;
* reproducibility.

Pipeline version does not need to appear prominently in the user interface.

---

# 54. Image Processing Determinism

Given identical input data and configuration, custom processing should produce deterministic output where practical.

Avoid randomness unless explicitly seeded.

Hardware/GPU floating-point differences may cause minor variation.

---

# 55. Exposure System

Default mode:

```text
AUTO
```

Auto exposure should initially rely heavily on the camera's reliable 3A system.

Application code should not attempt to reinvent auto exposure during MVP.

---

# 56. Exposure Modes

```kotlin
sealed interface ExposureMode {

    data object Auto : ExposureMode

    data class Manual(
        val iso: Int,
        val exposureTimeNs: Long
    ) : ExposureMode
}
```

Manual mode must only be available when sensor control is supported.

---

# 57. Exposure Compensation

When supported:

```text
UI EV
 ↓
requested EV
 ↓
camera-specific compensation index
```

Conversion must account for the camera's reported compensation step.

Do not assume one compensation index equals 1 EV.

---

# 58. Highlight Protection

The application may eventually bias automatic exposure toward highlight preservation.

For MVP:

* do not replace stable platform AE prematurely;
* provide exposure compensation;
* collect real-device observations.

A custom metering strategy may be introduced only after measurement.

---

# 59. Focus

Supported conceptual modes:

```kotlin
enum class FocusMode {
    CONTINUOUS,
    TAP_TO_FOCUS,
    LOCKED,
    MANUAL
}
```

Default:

```text
continuous autofocus appropriate for still photography
```

---

# 60. Tap to Focus

Viewfinder coordinates must be converted correctly to camera metering coordinates.

Must account for:

* preview crop;
* rotation;
* mirroring;
* aspect-ratio mismatch;
* selected camera;
* digital zoom if present.

Do not manually approximate this transformation if the selected camera API already exposes a reliable metering-point abstraction.

---

# 61. Focus Lock

Focus locking should preserve a stable lens position where supported.

Lock behavior must be released when:

* user unlocks;
* lens changes;
* camera changes;
* relevant camera session restarts.

---

# 62. Manual Focus

Manual focus should use camera-reported focus-distance capabilities.

UI must not expose fake infinity/macro ranges.

Manual focus should be hidden for fixed-focus cameras.

---

# 63. White Balance Controls

Default:

```text
AUTO
```

Future Pro mode can expose:

```text
presets
manual Kelvin
manual tint
```

Manual Kelvin should only be implemented when a technically sound conversion into camera controls or custom processing exists.

Do not present arbitrary Kelvin numbers disconnected from actual behavior.

---

# 64. Flash

Initial modes:

```text
OFF
AUTO
ON
```

Torch is separate from photographic flash.

Front-camera screen flash may be introduced where supported.

Flash capture must correctly coordinate:

```text
AE
AF
precapture
capture
```

depending on backend.

---

# 65. Stabilization

Image stabilization may include:

```text
optical stabilization
electronic stabilization
```

For still photography:

prefer hardware behavior that does not contradict PURE/NATURAL image intent.

Do not disable optical stabilization merely because the product minimizes computational processing.

Stabilization is not inherently equivalent to image enhancement.

---

# 66. Preview

Preview requirements:

```text
responsive
correct orientation
correct mirroring
correct aspect crop
lifecycle safe
low latency
```

Preview does not need to be pixel-identical to final output.

However, exposure and framing differences must not surprise the user unnecessarily.

---

# 67. Natural Preview

Future NATURAL preview processing may approximate:

* tone;
* color;
* exposure.

It must not delay MVP if the final capture output is already correct.

Initial implementation may use standard camera preview.

---

# 68. Capture Coordinator

Photography must be coordinated through a dedicated capture state machine.

Conceptual flow:

```text
IDLE
  ↓
PREPARING
  ↓
FOCUSING [if needed]
  ↓
METERING [if needed]
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

Any recoverable error must return to a usable state.

---

# 69. CaptureState

Example:

```kotlin
sealed interface CaptureState {

    data object Idle : CaptureState

    data object Preparing : CaptureState

    data object Capturing : CaptureState

    data class Processing(
        val progress: Float?
    ) : CaptureState

    data object Saving : CaptureState

    data class Complete(
        val result: SavedPhoto
    ) : CaptureState

    data class Failed(
        val error: CaptureError
    ) : CaptureState
}
```

---

# 70. PhotoCaptureRequest

```kotlin
data class PhotoCaptureRequest(
    val cameraId: CameraId,
    val profile: ProcessingProfile,
    val rawMode: RawMode,
    val exposureMode: ExposureMode,
    val focusMode: FocusMode,
    val flashMode: FlashMode,
    val outputSettings: OutputSettings
)
```

All information required to reproduce the capture decision should be explicit.

---

# 71. PhotoCaptureResult

```kotlin
data class PhotoCaptureResult(
    val finalPhoto: SavedPhoto?,
    val rawPhoto: SavedPhoto?,
    val metadata: CaptureMetadata,
    val processingProfile: ProcessingProfile,
    val pipelineVersion: String,
    val limitations: Set<CaptureLimitation>
)
```

---

# 72. Shutter Behavior

Shutter should respond immediately to user input where possible.

A visual capture acknowledgement must appear without waiting for full image processing.

Example:

```text
tap shutter
 ↓
capture indication
 ↓
camera available for next action when safe
 ↓
background processing
 ↓
gallery update
```

Do not block the main UI until JPEG encoding completes.

---

# 73. Burst Behavior

Burst capture is not an MVP requirement.

The architecture should avoid decisions that make burst impossible later.

But no early optimization should compromise single-shot image quality or stability merely for hypothetical burst support.

---

# 74. Concurrent Processing

Processing jobs should have explicit concurrency limits.

Example:

```text
maximum N outstanding full-resolution jobs
```

where N is derived from memory/performance constraints.

Do not allow unlimited RAW processing tasks.

---

# 75. Backpressure

If processing cannot keep up with captures:

options may include:

```text
queue captures
temporarily limit shutter
reduce preview processing
```

Never silently discard a user capture after shutter confirmation.

---

# 76. Threading

Never perform heavy image processing on:

```text
main thread
camera callback thread
Compose runtime
```

Use:

```text
coroutines
dedicated dispatcher/executor
```

Camera state changes requiring serialization should use controlled synchronization.

---

# 77. Memory Strategy

Full-resolution RAW processing can consume significant memory.

Requirements:

* minimize buffer copies;
* close `Image` instances promptly;
* reuse buffers where safe;
* avoid storing several decoded full-resolution images unnecessarily;
* stream encode where technically appropriate.

Memory usage must be benchmarked on mid-range devices, not only flagships.

---

# 78. Native Memory

When working with direct/native buffers:

ownership must be explicit.

Every buffer must have a predictable lifetime.

Avoid memory whose ownership is shared ambiguously between:

```text
camera
pipeline
encoder
UI
```

---

# 79. NDK Policy

C++/NDK is not required for initial implementation.

Introduce native code only for a demonstrated performance requirement.

Candidate future workloads:

```text
demosaicing
noise reduction
large color transformations
high-resolution convolution
```

Any native module must provide measurable improvement over maintainable Kotlin/platform code.

---

# 80. GPU Policy

GPU processing may eventually use platform-compatible compute/rendering mechanisms.

Potential targets:

```text
color transforms
tone mapping
demosaicing
denoise
sharpening
```

GPU acceleration must not become a hard MVP dependency before image correctness is established.

CPU reference implementations are valuable for validation.

---

# 81. JPEG Encoding

JPEG encoder configuration should expose:

```text
quality
dimensions
color information
orientation handling
metadata
```

Default quality should prioritize visible image quality without creating unnecessarily huge files.

Exact quality value should be determined through testing.

---

# 82. HEIF

HEIF/HEIC is a post-MVP feature unless implementation proves trivial and robust.

The encoder abstraction must allow:

```text
JPEG
HEIF
future formats
```

without modifying camera capture logic.

---

# 83. OutputSettings

```kotlin
data class OutputSettings(
    val format: FinalImageFormat,
    val jpegQuality: Int,
    val locationTagging: Boolean
)
```

---

# 84. Storage

Final photographs must use Android's standard media storage mechanisms.

Use:

```text
MediaStore
```

for user-visible images.

Suggested relative path:

```text
Pictures/<AppName>/
```

Exact app name is a product decision.

---

# 85. Temporary Storage

Temporary files may be used when required for:

* RAW;
* processing;
* encoding.

They must not accumulate permanently.

Cleanup should occur:

```text
on success
on failure
during safe application startup maintenance
```

---

# 86. File Naming

Suggested scheme:

```text
YYYYMMDD_HHMMSS_SSS
```

Example:

```text
20260925_214532_184.jpg
```

RAW companion:

```text
20260925_214532_184.dng
```

RAW and final files from one capture should share a common base identifier.

---

# 87. Capture Identity

Every shutter event should receive a unique capture ID.

```kotlin
@JvmInline
value class CaptureId(
    val value: String
)
```

Use capture ID to associate:

```text
final image
RAW
metadata
logs
processing job
```

---

# 88. EXIF Metadata

Where available and appropriate, final output should preserve:

```text
date/time

orientation

camera manufacturer

camera model

ISO

exposure time

aperture

focal length

35mm-equivalent focal length if valid

flash state

white balance

lens information
```

Do not fabricate unavailable EXIF data.

---

# 89. Orientation

Prefer physically correct output orientation.

Do not rely unnecessarily on consumers respecting EXIF rotation.

The exact policy should balance:

* memory;
* encoding speed;
* compatibility.

At minimum the saved image and metadata must agree.

---

# 90. Location Metadata

Default:

```text
OFF
```

When enabled:

```text
request permission
 ↓
obtain location
 ↓
attach EXIF location
```

Camera capture must continue to work when location permission is denied.

Location must never be required for photography.

---

# 91. Settings

MVP settings:

```text
Default processing profile

RAW mode

Grid

Level indicator

Location tagging

Volume button behavior

Keep screen awake

Device capability information
```

Processing-engine tuning parameters should remain internal initially.

Do not expose confusing controls purely because they exist.

---

# 92. Settings Persistence

Use:

```text
DataStore
```

for user settings.

A database is not justified for MVP preference storage.

---

# 93. Main Camera UI

Default camera UI should be minimal.

Conceptual structure:

```text
┌────────────────────────────┐
│ Flash                 RAW  │
│                            │
│                            │
│         VIEWFINDER         │
│                            │
│                            │
│ PURE   NATURAL   SYSTEM    │
│                            │
│       0.6×   1×   3×       │
│                            │
│ Gallery       ●        EV  │
└────────────────────────────┘
```

Exact arrangement is a design concern.

Technical requirement:

critical capture controls must remain accessible with one hand.

---

# 94. Profile UI

Profile selection must communicate processing philosophy rather than technical implementation.

User sees:

```text
PURE
NATURAL
SYSTEM
```

Not:

```text
RAW pipeline
YUV pipeline
Camera2 mode
```

Implementation details belong in diagnostics.

---

# 95. Device Capability Screen

Provide an optional technical screen.

Example:

```text
Main Camera

RAW                       Supported
Manual sensor             Supported
Manual post-processing    Supported
Noise reduction OFF       Supported
Edge OFF                  Supported
Manual focus              Supported
ISO                       50–6400
Exposure                   ...
```

This feature supports the PRD's transparency principle.

---

# 96. Capability Limitations

The capability screen should be able to state limitations such as:

```text
PURE uses minimal hardware noise reduction on this camera because full OFF is unavailable.
```

or:

```text
RAW capture is not exposed for the ultrawide camera.
```

Use factual wording.

---

# 97. Advanced / Pro Controls

Post-MVP Pro UI may expose:

```text
ISO
shutter
manual focus
white balance
histogram
focus peaking
clipping indicators
```

These controls must not clutter the default shooting experience.

---

# 98. Histogram

When implemented, histogram should be computed from a preview or analysis stream.

It must not interfere with capture performance.

Clearly define whether histogram represents:

```text
preview rendering
camera YUV
processed NATURAL output approximation
```

Do not imply sensor-level accuracy when unavailable.

---

# 99. Focus Peaking

Focus peaking is post-MVP.

Implementation may analyze luminance edges from a preview/analysis stream.

It should affect preview overlays only.

Never bake focus-peaking graphics into captured images.

---

# 100. Grid and Level

Grid:

pure UI overlay.

Level:

may use device sensors.

These features must not influence image processing.

---

# 101. Permissions

Required permissions should be minimized.

Likely requirements:

```text
CAMERA
```

Conditional:

```text
location permission
```

Storage behavior should use modern Android media APIs rather than broad legacy storage permissions whenever possible.

---

# 102. Offline Architecture

No network access is required for:

```text
camera preview
capture
PURE
NATURAL
SYSTEM
RAW
encoding
saving
gallery
```

The core application should remain fully functional in airplane mode.

---

# 103. Analytics

Analytics is not required for MVP.

If later introduced, it must never contain:

```text
captured images
RAW data
precise image contents
EXIF GPS
```

Camera functionality must never depend on analytics availability.

---

# 104. Error Taxonomy

Use typed domain errors.

```kotlin
sealed interface CameraError {

    data object PermissionDenied : CameraError

    data object CameraUnavailable : CameraError

    data object CameraDisconnected : CameraError

    data object SessionConfigurationFailed : CameraError

    data object UnsupportedConfiguration : CameraError

    data object CaptureFailed : CameraError

    data object RawUnavailable : CameraError

    data object ProcessingFailed : CameraError

    data object EncodingFailed : CameraError

    data object StorageFailed : CameraError
}
```

Unexpected implementation exceptions should be mapped into domain errors after being recorded for debugging.

---

# 105. Capture Failure

If final processing fails after successful capture:

attempt to preserve useful captured data where practical.

Example:

```text
RAW captured successfully
NATURAL processing failed
```

Possible result:

```text
retain RAW
report final image failure
```

Never silently delete the only successful capture unnecessarily.

---

# 106. Camera Recovery

On recoverable camera errors:

```text
close invalid session
 ↓
re-enumerate if required
 ↓
re-open selected camera
 ↓
restore compatible settings
```

Recovery attempts should be bounded.

Do not create infinite restart loops.

---

# 107. Vendor Compatibility

Create a dedicated compatibility layer.

Conceptual:

```text
compat/

CameraCompatibilityResolver

DeviceQuirk

CaptureQuirk

RawQuirk
```

Prefer:

```text
capability-driven logic
```

before:

```text
manufacturer-specific logic
```

---

# 108. Device Quirks

A vendor/device workaround is acceptable only when:

1. the problem is reproducible;
2. affected device/build is known;
3. generic camera behavior cannot solve it;
4. workaround is isolated;
5. behavior is documented;
6. regression test exists where practical.

Do not scatter manufacturer checks throughout business logic.

---

# 109. Logging

Debug logs should include:

```text
capture ID

camera ID

physical camera ID

capability scan

selected profile

capture source

capture plan

requested camera controls

capture-result metadata

processing pipeline version

processing duration

encoding duration

save duration

errors
```

Never log image pixel buffers.

---

# 110. Diagnostic Export

Future developer builds may export a diagnostic report containing:

```text
device info
Android build
camera capabilities
capture configuration
timings
pipeline versions
```

No photograph data should be included unless explicitly selected by the developer/user.

---

# 111. Performance Metrics

Measure:

```text
cold camera startup

warm camera startup

preview ready time

shutter acknowledgement

capture latency

RAW acquisition

YUV acquisition

processing latency

encoding latency

MediaStore latency

peak memory
```

Performance decisions must be based on measurements.

---

# 112. Performance Targets

Exact hard numbers should be determined after prototype benchmarking across real devices.

General targets:

```text
camera feels immediate

preview remains smooth

shutter feedback feels immediate

processing never freezes UI

memory usage remains bounded
```

Image quality and capture reliability take priority over artificially low post-processing latency.

---

# 113. Quality Profiles by Device

The application may internally select processing implementation based on device performance.

Example:

```text
same NATURAL intent

high-performance device:
    higher-resolution processing

memory-constrained device:
    memory-efficient implementation
```

Visible photographic intent should remain consistent.

Do not expose such internal implementation levels as image-quality marketing tiers without a product requirement.

---

# 114. Image Regression Tests

Maintain reference images for custom processing.

Test cases should include:

```text
daylight

skin tones

green foliage

blue sky

high dynamic range

warm indoor light

mixed lighting

low light

high ISO

fine texture

saturated colors

near-black tones

near-white highlights
```

---

# 115. Numerical Tests

Processing-stage tests should verify:

```text
finite output

expected channel ranges

matrix correctness

clamping

orientation

stride handling

black-level handling

white-level handling

CFA mapping

color transform
```

---

# 116. Golden Tests

Custom processing should support golden or tolerance-based image tests.

Avoid requiring exact bit-for-bit equality where:

* GPU implementations differ;
* floating-point rounding differs.

Use measurable tolerances.

---

# 117. Camera Instrumentation Tests

Where possible test:

```text
camera enumeration

open / close lifecycle

lens selection

focus

EV compensation

flash

capture

RAW support

orientation

MediaStore output
```

Emulators are useful for UI and lifecycle testing but are insufficient for validating image-quality behavior.

---

# 118. Real Device Testing

Real-device testing is mandatory.

Initial vendor diversity should include, where hardware is available:

```text
Google Pixel

Samsung Galaxy

Xiaomi / Redmi

Oppo / OnePlus / Realme
```

Additional vendors should be added as reports appear.

---

# 119. Device Test Record

Each real-device test should record:

```text
device

Android version

build

camera/lens

profile

RAW capability

selected capture path

known limitation

result
```

This becomes the compatibility knowledge base.

---

# 120. Image Comparison Harness

Development tooling should eventually allow side-by-side comparison:

```text
PURE
NATURAL
SYSTEM
```

from similar scenes.

Comparison should support:

```text
100% crop
histogram
metadata
pipeline timing
```

The tool is for engineering validation, not necessarily production UI.

---

# 121. Natural Pipeline Evaluation

When evaluating NATURAL changes, inspect:

```text
skin texture

hair

foliage

fine text

edge halos

shadow noise

highlight transitions

color neutrality

white balance

midtone contrast
```

Do not rely only on overall thumbnail appearance.

---

# 122. Feature Flags

Experimental image processing must use internal flags.

Example:

```text
raw_custom_pipeline

gpu_demosaic

natural_tone_v2

new_color_matrix

temporal_denoise

heif_output
```

Experimental algorithms must not alter release behavior unintentionally.

---

# 123. Pipeline A/B Development

Development builds may compare multiple internal processing versions.

Example:

```text
natural-v1

vs

natural-v3
```

This is engineering validation.

The production user should not need to choose between experimental algorithms.

---

# 124. Build Types

Suggested:

```text
debug

benchmark

release
```

Debug may expose:

```text
camera metadata
capability diagnostics
pipeline selection
timings
experimental profiles
```

Release should hide unnecessary engineering controls.

---

# 125. Security

The application should not expose captured image data through:

* world-readable temp files;
* unsafe IPC;
* debug services in release builds.

Temporary files should use application-private storage until intentionally committed to MediaStore.

---

# 126. Dependency Policy

Before introducing a library, confirm:

```text
why Android SDK is insufficient

maintenance status

binary impact

license

privacy impact

network behavior

image-quality behavior
```

Avoid large camera/image frameworks unless they materially solve a project requirement.

---

# 127. Versioning

Application code should independently version:

```text
app version

camera compatibility database

processing pipeline
```

This allows determining whether an output change came from:

```text
application behavior
device compatibility adjustment
image pipeline adjustment
```

---

# 128. Migration Safety

Settings changes must have explicit defaults.

If a profile's implementation changes significantly between versions:

do not silently reinterpret old custom configuration in a way that produces broken results.

Migrations should be deterministic.

---

# 129. MVP Scope

MVP implementation consists of:

```text
Camera preview

Camera discovery

Capability scanner

Main/back camera selection

Physical lens selection where supported

NATURAL profile

PURE profile

SYSTEM path

JPEG output

RAW output where supported

RAW + final output

Automatic exposure

Autofocus

Tap to focus

Exposure compensation

Flash

Gallery shortcut

MediaStore saving

Metadata

Orientation handling

Basic settings

Capability information

Typed errors

Real-device compatibility testing
```

---

# 130. MVP Natural Pipeline

The first NATURAL pipeline should intentionally remain simple.

Recommended first implementation:

```text
RAW/YUV input
 ↓
correct input normalization
 ↓
correct white balance
 ↓
correct color conversion
 ↓
simple global tone curve
 ↓
smooth highlight roll-off
 ↓
very restrained denoise
 ↓
very restrained sharpening
 ↓
JPEG
```

Do not start with:

```text
AI denoise

multi-frame super resolution

local HDR

semantic segmentation

face-specific enhancement
```

---

# 131. MVP PURE Pipeline

First PURE implementation:

```text
obtain least processed supported input
 ↓
perform only transformations required
   to generate a viewable final image
 ↓
JPEG
```

PURE may contain:

```text
visible grain

lower perceived sharpness

lower local contrast
```

Those are not automatically bugs.

---

# 132. Post-MVP

Possible future work:

```text
HEIF / HEIC

wide-gamut output

10-bit image pipeline

Ultra HDR experimentation

custom manual white balance

manual ISO

manual shutter

manual focus

histogram

focus peaking

clipping warnings

calibrated per-device profiles

improved demosaic

advanced chroma denoise

device-specific color calibration

optional restrained multi-frame capture

image comparison mode

custom looks / LUTs
```

Any addition must remain consistent with the PRD.

---

# 133. Multi-Frame Processing

Multi-frame photography is not forbidden permanently.

However it must not be introduced merely to increase:

```text
HDR appearance
brightness
sharpness
```

A future multi-frame implementation would need to demonstrate that it improves:

```text
noise
detail
dynamic range
```

without violating the natural-rendering philosophy.

---

# 134. AI Processing

AI-based image enhancement is not an MVP requirement.

Future ML features must not silently introduce:

```text
skin beautification

face reshaping

semantic color changes

fabric smoothing

sky replacement

detail hallucination
```

Any ML operation must preserve photographic authenticity.

---

# 135. Architecture Decision Records

Significant architectural decisions should be documented.

Examples:

```text
ADR-001 CameraX vs direct Camera2

ADR-002 RAW processing representation

ADR-003 working color space

ADR-004 JPEG encoder

ADR-005 GPU acceleration
```

Each ADR should explain:

```text
context
decision
alternatives
trade-offs
```

---

# 136. Implementation Sequence

Recommended development order:

```text
01. Project skeleton

02. Camera enumeration

03. Capability scanner

04. Preview

05. Standard JPEG capture

06. MediaStore saving

07. Orientation + EXIF

08. Lens selection

09. Tap-to-focus

10. Exposure compensation

11. Flash

12. Processing profile domain

13. SYSTEM implementation

14. RAW support

15. RAW + JPEG

16. PURE capture configuration

17. YUV processing foundation

18. RAW decoding foundation

19. NATURAL color pipeline

20. NATURAL tone pipeline

21. Restrained denoise

22. Restrained sharpening

23. Device capability UI

24. Compatibility layer

25. Real-device regression suite

26. Performance optimization
```

This sequence intentionally builds a stable camera before advanced image processing.

---

# 137. First Engineering Milestone

The first major milestone is not NATURAL.

It is:

> a reliable camera foundation that correctly understands the selected Android device.

Milestone requirements:

```text
preview works

camera selection works

capability detection is reliable

JPEG capture works

MediaStore works

orientation works

metadata works

no frame leaks

camera survives lifecycle changes
```

Only then should major custom processing begin.

---

# 138. Second Engineering Milestone

The second milestone:

> demonstrate that PURE can produce a meaningfully less processed result on at least one supported device.

Required validation:

```text
side-by-side SYSTEM vs PURE

verified camera configuration

100% crop review

metadata

documented hardware limitations
```

---

# 139. Third Engineering Milestone

The third milestone:

> produce a NATURAL image that is immediately usable without reproducing OEM overprocessing.

Validation scenes should include:

```text
daylight

human skin

foliage

indoor lighting

high dynamic range

moderate low light
```

---

# 140. Definition of Technical Success

The architecture is successful when:

1. the same application works across materially different Android camera implementations;

2. camera capabilities are discovered rather than guessed;

3. unsupported controls degrade honestly;

4. PURE actually chooses the least processed practical path available;

5. NATURAL produces finished images through an application-controlled restrained pipeline where possible;

6. SYSTEM remains available for comparison/fallback;

7. RAW works independently from the final-image experience;

8. the UI does not need to know whether CameraX, Camera2, RAW, or YUV produced the photograph;

9. processing changes can be developed and tested without rewriting camera lifecycle code;

10. device-specific workarounds remain isolated.

---

# 141. Definition of Done for Camera Features

A camera feature is not complete until:

```text
[ ] Requirement traced to PRD

[ ] Capability detection implemented

[ ] Supported path implemented

[ ] Unsupported path handled

[ ] Camera lifecycle handled

[ ] Errors typed

[ ] No Image/frame leak

[ ] UI state correct

[ ] Real-device test completed where applicable
```

---

# 142. Definition of Done for Image Processing

An image-processing change is not complete until:

```text
[ ] Profile intent identified

[ ] Input/output range documented

[ ] Algorithm documented

[ ] No unexplained magic constants

[ ] Numerical tests added

[ ] Visual test scenes reviewed

[ ] Texture reviewed at 100%

[ ] Highlight behavior reviewed

[ ] Color behavior reviewed

[ ] Performance measured

[ ] Memory measured

[ ] Before/after samples compared

[ ] No accidental beautification

[ ] No accidental aggressive sharpening

[ ] No accidental aggressive denoising
```

---

# 143. Final Technical Rule

Whenever implementation choices conflict between:

```text
more computational enhancement
```

and:

```text
more faithful natural rendering
```

follow the intent established by `PRD.md`.

The engineering objective is not to reproduce the processing behavior of a stock Android camera.

The engineering objective is:

> to build a reliable camera stack that obtains the cleanest practical camera data available from each device and turns it into a restrained, natural, immediately usable photograph.
