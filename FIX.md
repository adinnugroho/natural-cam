You are working on an Android natural-camera application.

IMPORTANT SOURCE OF TRUTH:
1. Read PRD.md first.
2. Read SPEC.md second.
3. Read AGENTS.md third.
4. Do not change product intent to make implementation easier.
5. PRD.md wins over every other document.

The current NATURAL image output has a visible image-quality regression.

Observed real-device output:
- Overall exposure is too dark.
- Midtones are significantly too low.
- Shadows are unnecessarily crushed / muddy.
- Outdoor daylight images have a noticeable cyan / blue cast.
- Some shadows also lean slightly green.
- Neutral surfaces do not appear sufficiently neutral.
- Highlights are generally preserved well.
- Texture preservation is already good.
- Sharpening does not appear excessive.
- Denoising does not appear excessive.

DO NOT solve this by applying a generic aesthetic filter.

Specifically DO NOT simply add things like:

brightness *= 1.2
saturation *= 1.1
red *= 1.05
blue *= 0.9

Do not introduce arbitrary global RGB multipliers, arbitrary warmth filters, aggressive shadow lifting, HDR-like local tone mapping, or global saturation boosts.

The problem must be investigated and corrected at the appropriate stages of the imaging pipeline.

==================================================
PRIMARY GOAL
==================================================

Improve the NATURAL pipeline so that:

1. daylight does not have an excessive cyan/blue cast;
2. neutral surfaces remain reasonably neutral;
3. midtones are properly exposed;
4. shadows remain readable without becoming HDR-like;
5. highlights remain protected;
6. black levels still look photographic;
7. texture remains natural;
8. sharpening and denoising stay restrained.

The target is:

natural finished photograph

NOT:

flat RAW preview

and NOT:

OEM-style aggressively processed photograph.

==================================================
DO NOT CHANGE YET
==================================================

Unless investigation proves they are directly responsible, do NOT substantially modify:

- sharpening
- denoising
- JPEG quality
- image resolution
- UI
- camera selection
- capture architecture

The current problem should initially be treated as a:

- white balance issue;
- color transform issue;
- exposure normalization issue;
- tone mapping issue.

==================================================
STEP 1 — TRACE THE CURRENT PIPELINE
==================================================

Before changing code, inspect the actual image path used by NATURAL.

Document the exact current path.

For example:

RAW / YUV
→ normalization
→ white balance
→ camera color transform
→ exposure adjustment
→ tone curve
→ highlight roll-off
→ denoise
→ sharpening
→ output transform
→ JPEG

Do not assume this is the actual path.

Determine it from the existing source code.

Identify:

- input format used by NATURAL;
- whether the input is RAW, YUV, or already processed;
- color space of the input;
- working color space;
- output color space;
- where white balance is applied;
- where exposure is modified;
- where tone mapping occurs;
- where gamma / transfer functions are applied.

Report any suspicious duplicated transformation.

Examples:

white balance applied twice;
gamma applied too early;
camera matrix applied twice;
YUV converted using wrong coefficients;
linear values interpreted as gamma values;
incorrect RGB channel order.

==================================================
STEP 2 — WHITE BALANCE INVESTIGATION
==================================================

The current daylight output is too cyan / blue.

Inspect how white balance is currently determined.

If RAW is used, verify use of the appropriate capture metadata such as available neutral-color / gain information.

Do not assume fixed RGB gains.

Verify:

- channel gain ordering;
- CFA channel mapping;
- whether gains are applied before/after demosaic correctly;
- whether WB is accidentally applied twice;
- whether a green channel normalization error exists;
- whether metadata units are interpreted correctly.

If YUV is used, determine whether the camera stream already includes AWB.

If AWB is already baked into YUV, do NOT apply RAW-style white balance a second time.

Inspect neutral surfaces.

The desired condition is approximately:

R ≈ G ≈ B

for genuinely neutral objects after color transformation, within reasonable photographic tolerance.

Do NOT force all scenes toward mathematically neutral gray.

Preserve actual scene illumination.

==================================================
STEP 3 — CAMERA COLOR TRANSFORM
==================================================

Inspect the conversion:

camera/sensor color space
→ working RGB
→ output RGB.

Verify:

- matrix direction;
- matrix multiplication order;
- matrix normalization;
- white point;
- channel order;
- whether matrices need inversion;
- whether transforms operate in linear space;
- whether the correct matrix for the current illuminant is selected/interpolated.

Do not assume sensor RGB is sRGB.

Look specifically for a transform that could explain:

low red response
+
excessive green/blue response.

Create or improve a centralized component such as:

CameraColorTransformer

rather than fixing color using arbitrary values later in the pipeline.

==================================================
STEP 4 — EXPOSURE NORMALIZATION
==================================================

Current NATURAL output appears approximately 0.5–1 EV too dark in many scenes.

Do NOT simply add +0.7 EV globally.

Instead inspect:

- camera AE result;
- RAW normalization;
- black-level subtraction;
- white-level normalization;
- exposure scaling;
- tone mapper input range;
- transfer-function handling.

Determine whether the image is genuinely captured too dark or whether the custom pipeline is rendering correctly captured data too dark.

These are different problems.

Add development diagnostics that can distinguish:

CAPTURE EXPOSURE

from

RENDERING EXPOSURE.

For RAW captures log, where available:

- SENSOR_EXPOSURE_TIME
- SENSOR_SENSITIVITY
- aperture
- AE state
- exposure compensation
- black level
- white level

Do not include image data in logs.

==================================================
STEP 5 — NATURAL TONE CURVE
==================================================

Review the current NATURAL tone mapper.

The current behavior appears to protect highlights well but pushes midtones and shadows too low.

Modify the tone curve so that it:

- maintains a real black point;
- slightly lifts deep-to-middle shadows;
- noticeably improves middle-gray readability;
- leaves bright midtones natural;
- preserves the existing good highlight protection;
- uses a smooth highlight shoulder.

The desired conceptual response is:

black
  |
  | gentle toe
  |
  |      slightly lifted midtones
  |             /
  |           /
  |         /
  |       /
  |_____/________________
                     smooth shoulder
                           → white

Avoid a flat HDR look.

Do NOT make all dark regions bright.

A dark room must still look dark.

But visible objects should not disappear unnecessarily.

==================================================
STEP 6 — LINEAR VS NON-LINEAR CHECK
==================================================

Audit where operations occur.

Confirm that operations involving physical light relationships are performed in linear space where appropriate.

In particular inspect:

- exposure compensation;
- white balance;
- color matrices;
- highlight handling.

Do not blindly multiply gamma-encoded RGB for exposure correction.

Also verify the final transfer function is applied exactly once.

A double gamma / incorrect inverse gamma can easily produce images that are too dark.

==================================================
STEP 7 — BLACK LEVEL
==================================================

Inspect RAW black-level handling if RAW is involved.

Verify the equivalent of:

normalized =
(raw - blackLevel)
/
(whiteLevel - blackLevel)

Check:

- per-channel black levels;
- Bayer pattern mapping;
- clamping;
- integer precision;
- sign/overflow errors.

Incorrect black subtraction may cause:

- crushed shadows;
- color casts in shadows;
- green/cyan shadow bias.

==================================================
STEP 8 — NATURAL PROFILE TUNING
==================================================

After correctness issues are fixed, small profile tuning is allowed.

Any empirical parameter must:

- live in a central NATURAL configuration;
- have a descriptive name;
- include a comment explaining its purpose;
- not be a random RGB correction.

Acceptable examples:

NaturalToneConfig(
    midtoneLift = ...,
    toeStrength = ...,
    shoulderStrength = ...
)

Do not use:

redCorrection = 1.07
blueCorrection = 0.91

unless this is part of a technically justified calibrated color transform.

==================================================
STEP 9 — DIAGNOSTICS
==================================================

Add debug-only diagnostics sufficient to understand the pipeline.

For each NATURAL capture, log:

capture ID
camera ID
capture source
pipeline version
white balance source
WB gains if relevant
input color space
working color space
output color space
black level
white level
exposure metadata
selected color transform
tone mapper version

Do not log pixel data.

If practical, allow debug builds to save intermediate image stages:

01_normalized
02_white_balanced
03_color_transformed
04_pre_tone
05_post_tone
06_final

This must be debug-only.

Do not expose intermediate files in production.

==================================================
STEP 10 — TESTS
==================================================

Add/update tests for:

1. neutral gray input
   Expected:
   no significant unintended RGB tint.

2. daylight neutral target
   Expected:
   not strongly cyan/blue.

3. shadow neutrality
   Expected:
   no strong green/cyan cast caused by pipeline math.

4. tone curve monotonicity
   Expected:
   increasing input always produces increasing output.

5. black preservation
   Expected:
   black remains black.

6. midtone response
   Expected:
   middle-gray values are not unintentionally suppressed.

7. highlight response
   Expected:
   smooth roll-off without abrupt clipping.

8. color-transform matrix correctness.

9. transfer-function correctness.

10. RAW black-level normalization if applicable.

==================================================
VISUAL ACCEPTANCE CRITERIA
==================================================

Test at minimum:

A. Outdoor daylight scene with:
- blue sky
- green foliage
- neutral wall/concrete.

Expected:

- sky still looks naturally blue;
- neutral wall does not become cyan;
- foliage does not become unnaturally teal;
- shadows do not have a strong green/cyan cast;
- scene does not look artificially warm.

B. Indoor room with one bright doorway/window.

Expected:

- bright source remains protected;
- room still looks darker than the exterior;
- indoor objects remain readable;
- shadows are not massively lifted;
- image does not look HDR.

C. Mixed-light scene.

Expected:

- preserve believable lighting differences;
- do not force entire image to one neutral temperature.

==================================================
IMPORTANT NATURAL MODE RULE
==================================================

Natural does NOT mean dark.

Natural does NOT mean flat.

Natural does NOT mean zero processing.

The NATURAL profile is a carefully developed photograph.

It should sit between:

PURE:
least processed practical output

and

SYSTEM:
OEM/platform processing.

Target:

PURE
   ↓
technically correct color development
   ↓
gentle photographic tone development
   ↓
NATURAL

NOT:

PURE
   ↓
generic brightness/filter
   ↓
NATURAL

==================================================
IMPLEMENTATION PROCESS
==================================================

Work incrementally.

Do NOT rewrite the whole image pipeline immediately.

First identify the actual root causes.

Before editing, report:

1. current NATURAL capture path;
2. current WB implementation;
3. current color transform;
4. current exposure logic;
5. current tone mapper;
6. likely causes of:
   - cyan/blue cast;
   - green shadows;
   - low midtones.

Then implement the smallest technically correct fix.

After implementation, provide:

1. files changed;
2. root cause found;
3. exact changes;
4. why each change fixes the problem;
5. any tuning parameters introduced;
6. tests added;
7. known remaining limitations;
8. which items still require real-device validation.

Do not modify PRD.md.

Only update SPEC.md if a discovered technical limitation or implementation decision needs documentation.

Keep AGENTS.md rules intact.