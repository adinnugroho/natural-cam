# PRD.md

## 1. Product Summary

This project is an Android camera application focused on producing natural-looking photographs with minimal computational photography.

The application should provide an alternative to the increasingly aggressive image processing commonly applied by smartphone camera applications, including excessive sharpening, denoising, HDR tone mapping, artificial contrast, saturation enhancement, skin smoothing, and other vendor-specific computational processing.

The product should allow the user to:

> Open the camera, press the shutter, and receive a natural-looking photograph that is immediately usable.

RAW capture is supported, but RAW is not the product itself.

The primary product is a high-quality, minimally processed final image.

---

# 2. Product Vision

Build the Android equivalent of a "no overprocessing" camera.

The application should prioritize:

* natural texture
* realistic skin
* restrained sharpening
* restrained denoising
* realistic contrast
* natural highlight roll-off
* realistic colors
* predictable processing
* user control over computational photography

The application must not attempt to make every photo look artificially impressive.

The desired result is:

> closer to the camera sensor, while still looking like a finished photograph.

---

# 3. Product Principles

## 3.1 Natural First

Natural rendering takes priority over:

* maximum HDR
* extreme dynamic range
* maximum perceived sharpness
* saturated colors
* social-media-ready processing

The image should preserve the visual characteristics of the scene whenever technically possible.

---

## 3.2 Minimal Computational Photography

The application should minimize or disable unnecessary:

* sharpening
* edge enhancement
* noise reduction
* local contrast enhancement
* beauty processing
* face retouching
* artificial saturation
* multi-frame HDR
* aggressive tone mapping

when supported by the hardware.

The application must never claim that processing is disabled when the device or camera HAL does not allow that level of control.

---

## 3.3 Finished Images, Not Just RAW

The application must generate photographs that can be used immediately.

A user should not need Lightroom or another RAW editor to make the image usable.

RAW/DNG is an optional companion output for users who want maximum editing flexibility.

---

# 4. Primary User

The primary user:

* likes smartphone photography
* dislikes aggressive smartphone image processing
* wants realistic texture and color
* wants a simple point-and-shoot workflow
* may understand photography but should not need technical knowledge to use the application

Secondary users include:

* photographers
* content creators
* mobile photography enthusiasts
* users who want RAW capture
* users who want manual camera controls

---

# 5. Core User Experience

The default experience must remain extremely simple.

Typical workflow:

1. Open application.
2. Camera preview appears.
3. Aim camera.
4. Press shutter.
5. Receive natural-looking photo.
6. Photo automatically appears in the device gallery.

No configuration should be required before taking the first photograph.

---

# 6. Capture Philosophy

The application has two conceptual processing paths.

## 6.1 Natural Output

Primary output.

Conceptual pipeline:

Camera Sensor

→ capture

→ demosaic / camera conversion

→ white balance

→ color transformation

→ restrained noise handling

→ restrained tone mapping

→ optional extremely mild sharpening

→ JPEG / HEIF

The final image should remain visually natural.

---

## 6.2 RAW Output

Optional secondary output.

Conceptual pipeline:

Camera Sensor

→ RAW capture

→ DNG

RAW files should preserve as much sensor information as the Android device exposes.

---

# 7. Processing Profiles

The application must provide three processing philosophies.

## 7.1 PURE

Goal:

Produce the least processed practical image supported by the device.

Preferred characteristics:

* HDR: disabled
* sharpening: disabled or minimum available
* denoising: disabled or minimum available
* saturation enhancement: none
* local contrast enhancement: none
* beauty processing: none
* artificial skin enhancement: none
* tone mapping: minimal
* color rendering: neutral
* multi-frame enhancement: disabled when possible

PURE does not guarantee zero processing.

If the Android camera HAL or device ISP performs unavoidable processing, the application must report the limitation honestly.

---

## 7.2 NATURAL

Default mode.

Goal:

Create a pleasant finished image while maintaining realistic rendering.

Characteristics:

* minimal noise reduction
* very mild sharpening when needed
* gentle tone mapping
* natural white balance
* natural colors
* controlled highlights
* realistic shadows
* no beauty processing
* no intentionally exaggerated HDR look

NATURAL should normally provide better straight-out-of-camera results than PURE.

---

## 7.3 SYSTEM

Provides the device's conventional camera processing where technically available.

Purpose:

* reference
* comparison
* compatibility fallback

The exact appearance may vary by device manufacturer.

SYSTEM must not be presented as equivalent across Android devices.

---

# 8. Main Camera Interface

The default camera UI should be intentionally minimal.

Primary controls:

* shutter
* camera switch
* lens selection
* exposure compensation
* gallery shortcut
* processing profile
* flash control

Optional quick controls:

* RAW
* timer
* aspect ratio

The screen must not resemble a complicated professional cinema-camera interface by default.

---

# 9. Lens Selection

Where supported, users should be able to select available physical cameras such as:

* ultrawide
* main
* telephoto
* front camera

The application must detect what each camera actually supports.

A lens must not automatically inherit capabilities from another lens.

Example:

Main camera:

RAW ✓
Manual exposure ✓
Minimal NR ✓

Ultrawide:

RAW ✕
Manual exposure ✓
Minimal NR ✕

The application should gracefully adapt to these differences.

---

# 10. Device Capability Detection

Capability detection is a fundamental product requirement.

On first launch and whenever necessary, the application should determine relevant camera capabilities.

Examples:

* RAW_SENSOR availability
* hardware support level
* manual exposure
* manual focus
* exposure compensation
* supported output formats
* supported resolutions
* noise-reduction modes
* edge/sharpening modes
* optical stabilization
* electronic stabilization
* physical camera availability
* dynamic-range capabilities
* flash
* minimum focus distance
* sensor sensitivity range
* exposure-time range

The user interface must only expose functionality supported by the selected camera.

---

# 11. Capability Transparency

The application must never silently pretend unsupported functionality exists.

Example:

If sharpening cannot be disabled:

Incorrect:

"Sharpening: OFF"

Correct:

"Sharpening: Minimum available"

or internally adapt NATURAL mode while reporting the limitation in device capabilities.

An optional Device Capability page should show users what their camera supports.

---

# 12. RAW Capture

RAW capture should be optional.

Modes:

* Final Image Only
* RAW + Final Image
* RAW Only

RAW format:

* DNG where supported

RAW should not be required for normal usage.

---

# 13. Auto Mode

The application must provide a reliable automatic exposure workflow.

Auto mode manages:

* shutter speed
* ISO
* autofocus
* white balance

The automatic system should prioritize photographic stability and natural rendering.

The processing engine and exposure engine are separate concepts.

NATURAL mode must not require manual exposure.

---

# 14. Manual / Pro Mode

Advanced users should be able to optionally control:

* shutter speed
* ISO
* manual focus
* white balance
* exposure compensation where relevant

Possible tools:

* histogram
* focus peaking
* exposure clipping indicators
* level indicator

These controls are secondary to the simple default experience.

---

# 15. Focus

Default:

Continuous autofocus appropriate for photography.

Interactions:

* tap to focus
* tap to meter where supported
* focus lock

Manual focus may be available in Pro mode when supported.

---

# 16. Exposure

Default exposure should aim to protect important highlights without intentionally producing excessively dark photographs.

Exposure compensation should be accessible directly from the main interface.

Future versions may introduce configurable metering behavior.

---

# 17. White Balance

Default:

Automatic white balance.

NATURAL processing should avoid unnecessary color shifts designed solely to make photographs visually warmer or more saturated.

Manual white balance can be provided in Pro mode.

---

# 18. Color

Color should aim for:

* realistic skin tones
* restrained saturation
* smooth gradients
* predictable rendering
* consistency across photographs from the same camera

The application does not need to make different Android devices look identical.

The goal is natural rendering within each device's available camera characteristics.

---

# 19. Noise

Noise is not automatically considered a defect.

The processing engine should prefer preserving fine detail over destroying texture through excessive denoising.

PURE may visibly contain sensor noise.

NATURAL may apply restrained noise treatment.

The application must not intentionally create the waxy or painted appearance caused by excessive denoising.

---

# 20. Sharpening

Sharpening should be conservative.

Priority:

real detail > perceived sharpness.

The application should avoid:

* halos
* oversharpened hair
* crunchy foliage
* excessive microcontrast
* exaggerated facial detail

PURE:

minimum or disabled sharpening when available.

NATURAL:

small amount only when necessary.

---

# 21. HDR

Aggressive HDR must not be the default behavior.

PURE:

multi-frame HDR should be disabled where possible.

NATURAL:

may use restrained dynamic-range handling when required, but must preserve a photographic appearance.

Any future multi-frame pipeline must remain consistent with the product's natural-processing philosophy.

---

# 22. Beauty and Face Processing

The application must not intentionally apply:

* skin smoothing
* face reshaping
* eye enlargement
* whitening
* facial enhancement
* body reshaping

No beautification system should be part of the default processing pipeline.

---

# 23. Preview

The live preview should approximate the expected final exposure and rendering where practical.

Perfect preview-to-output equivalence is not mandatory because capture pipelines differ across Android devices.

The application should minimize surprising differences between preview and final image.

---

# 24. Image Formats

Initial supported final formats:

* JPEG

Preferred future support:

* HEIF / HEIC where stable and supported

RAW:

* DNG

The implementation should keep image encoding separate from the processing pipeline so additional output formats can be introduced later.

---

# 25. Metadata

Final images should preserve appropriate metadata including where available:

* timestamp
* orientation
* focal length
* lens information
* ISO
* shutter speed
* aperture
* camera model

Location metadata must only be stored when the user explicitly enables location tagging and grants the required permission.

---

# 26. Gallery Integration

Captured images should appear normally in Android gallery applications.

Users should not be forced into a proprietary photo library.

The application may contain an internal recent-photo viewer, but standard Android storage remains authoritative.

---

# 27. Performance

The camera should feel responsive.

Priorities:

1. camera reliability
2. correct capture
3. image quality
4. shutter responsiveness
5. processing latency

The app must never sacrifice capture reliability merely to achieve unnecessarily complex effects.

---

# 28. Offline-First Requirement

Core photography functionality must work completely offline.

Image processing must happen on the user's device.

Cloud processing must not be required for:

* capture
* RAW creation
* NATURAL processing
* PURE processing
* saving photographs

---

# 29. Privacy

Captured photos remain on the user's device unless the user intentionally shares or exports them.

The application should not upload photographs for processing.

Analytics, if introduced, must never require uploading captured image content.

---

# 30. Device Fragmentation Strategy

Android hardware fragmentation is accepted as a core product constraint.

The architecture should follow:

Device capability

→ determine available camera controls

→ select safest supported capture path

→ apply application processing

→ produce consistent processing philosophy

The application should aim for consistent intent rather than mathematically identical output across every Android phone.

---

# 31. Compatibility Philosophy

Do not restrict the application only to flagship phones unnecessarily.

The application should provide the best supported mode for each device.

Possible capability tiers may include:

### Full

RAW and extensive manual ISP controls available.

### Partial

Some direct processing controls available.

### Basic

Limited low-level control; application provides the best possible rendering using accessible output.

These tiers describe available capabilities, not device quality.

---

# 32. Failure Handling

If a requested feature cannot operate on a selected camera:

* do not crash
* do not silently fabricate support
* fall back safely where possible
* explain unavailable controls where useful

Camera reliability is mandatory.

---

# 33. Non-Goals

Initial versions are not intended to become:

* a video production application
* a social media application
* a cloud photo editor
* an AI image generator
* a beauty camera
* a Lightroom replacement
* a computational photography benchmark
* a clone of another application's interface
* an exact recreation of Apple's camera pipeline

---

# 34. MVP

The first usable release should include:

* camera preview
* photo capture
* NATURAL profile
* PURE profile
* SYSTEM/fallback path where feasible
* JPEG output
* RAW/DNG when supported
* RAW + JPEG
* automatic exposure
* autofocus
* tap to focus
* exposure compensation
* lens switching
* flash
* capability detection
* automatic gallery saving
* basic settings
* reliable orientation handling
* metadata handling

---

# 35. Post-MVP

Possible later additions:

* HEIF
* histogram
* focus peaking
* manual ISO
* manual shutter
* manual focus
* manual white balance
* clipping indicators
* custom color profiles
* LUT support
* calibrated device profiles
* improved custom demosaic pipeline
* custom camera profiles
* image comparison view
* RAW development controls
* optional restrained multi-frame processing

These features must not compromise the product's core natural-rendering philosophy.

---

# 36. Success Criteria

The product succeeds when users can:

1. open the application quickly;
2. take a photograph without configuring technical settings;
3. receive a usable photograph immediately;
4. observe noticeably less aggressive processing than typical OEM camera output where hardware access permits;
5. retain realistic texture and color;
6. optionally obtain the corresponding RAW file;
7. understand when their device limits low-level camera control.

---

# 37. Product Identity

The product is not:

> another professional manual camera.

The product is:

> a simple Android camera built around natural rendering and user control over unwanted computational photography.

Its core promise is:

**Your camera, without unnecessary processing.**
