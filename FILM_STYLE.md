You are working on the existing Android natural-camera project.

Before making any change, read:

1. PRD.md
2. SPEC.md
3. AGENTS.md
4. STYLE_PLAN.md

Follow the existing authority hierarchy.

Do NOT change the core NATURAL pipeline to create this style.

The task is to add a new built-in Style preset inspired by the attached reference image.

==================================================
GOAL
==================================================

Create a new built-in photographic style with the visual character of the provided reference.

Working preset name:

WARM_STREET

Internal ID:

warm_street

This is a creative Style layered on top of a technically correct NATURAL image.

Do NOT use this preset to compensate for:

- bad white balance;
- incorrect exposure;
- incorrect camera color matrices;
- cyan cast bugs;
- broken tone mapping.

NATURAL must remain the neutral technical baseline.

==================================================
VISUAL TARGET
==================================================

The reference has a restrained warm filmic street-photography character.

Overall target:

- warm but not orange;
- muted but not desaturated;
- relatively deep blacks;
- moderately strong contrast;
- soft highlight rendering;
- warm creamy highlights;
- slightly cooler shadows;
- restrained blues;
- natural but slightly olive greens;
- warm beige / cream architecture;
- no HDR look;
- no excessive clarity;
- no aggressive sharpening;
- no crushed shadow detail;
- no obvious filter overlay.

The image should still feel photographic and believable.

It should NOT look like:

- Instagram filter;
- orange/teal grading;
- vintage sepia;
- faded brown filter;
- oversaturated film simulation;
- aggressive cinematic grade.

==================================================
REFERENCE CHARACTER
==================================================

Approximate tonal behavior:

SHADOWS

Deep and fairly dense.

The black point should feel confident, but shadow texture should still remain visible.

Do not lift shadows aggressively.

Avoid completely clipping dark architecture and street areas.

Desired impression:

deep
clean
slightly cool
photographic

not:

crushed
milky
HDR-lifted

--------------------------------------------------

MIDTONES

Midtones should have moderately strong density.

Buildings and street surfaces should feel slightly richer and darker than standard NATURAL.

Do not simply reduce exposure.

The style should use tone-curve shaping.

The midtone character should feel:

dense
warm-neutral
film-like

--------------------------------------------------

HIGHLIGHTS

Highlights should be soft and creamy.

Bright concrete / walls / sky should not feel sterile blue-white.

Use a smooth shoulder.

Preserve highlight texture where possible.

Do not create gray HDR highlights.

Desired highlight color character:

neutral white
→ slightly warm cream

NOT:

yellow
orange
pink

==================================================
COLOR CHARACTER
==================================================

The style should be based on a controlled warm/cool relationship.

Highlight / bright-mid color tendency:

slightly warm

Shadow tendency:

neutral to slightly cool

This must be subtle.

Do NOT create an exaggerated split-tone look.

==================================================
BLUE / CYAN
==================================================

Blue should be restrained.

Sky should remain naturally blue when present, but should not dominate the photograph.

Recommended behavior:

- slightly lower blue saturation;
- prevent strong cyan rendering;
- keep blue luminance natural;
- preserve real blue objects.

Do NOT globally subtract blue RGB.

Use hue-aware palette processing.

==================================================
GREEN
==================================================

Greens should remain present but slightly more organic / olive.

Reference behavior:

bright digital green
→ slightly warmer / olive photographic green

Do not turn greens yellow.

Do not heavily desaturate foliage.

Do not make skin inherit the green treatment.

==================================================
YELLOW / ORANGE
==================================================

Warm architecture and sunlight should gain a small amount of richness.

Yellow should move slightly toward a warm cream / amber character.

Orange should remain restrained.

Avoid strong orange skin.

==================================================
RED / MAGENTA
==================================================

Keep reds relatively natural.

A small warm bias is acceptable.

Do not create strong magenta shadows.

Do not shift skin toward pink/red unnecessarily.

==================================================
SATURATION
==================================================

Overall saturation should be slightly below the NATURAL baseline.

However, do NOT simply reduce global saturation.

Prefer selective chroma shaping:

blue/cyan:
slightly reduced

green:
slightly reduced / shifted toward organic olive

yellow/orange:
preserved or slightly enriched

red:
mostly preserved

skin range:
protected

The final image should feel colorful enough to remain alive.

==================================================
TONE STYLE
==================================================

Use the existing Tone Style architecture.

The reference approximately corresponds to:

- slightly firmer contrast;
- slightly deeper midtones;
- strong but controlled black point;
- gentle highlight shoulder.

Do NOT implement:

brightness -= X

or:

contrast *= X

directly on the final encoded image.

Resolve this through the existing ToneStyleProcessor / ToneMapper system.

Suggested initial normalized Style Pad starting point:

Tone:

x = +0.15
y = -0.25

Where:

x:
soft <-> firm

y:
deeper <-> brighter

These values are only initial tuning targets.

Do not treat them as immutable truth.

Tune them using the actual StyleParameterResolver.

==================================================
COLOR STYLE
==================================================

Use the existing Color Style system.

Target:

slightly warm
slightly muted

Suggested initial Style Pad starting point:

Color:

x = +0.20
y = -0.15

Where:

x:
cool <-> warm

y:
muted <-> rich

Again:

these are normalized starting points only.

Do not bypass the StyleParameterResolver.

==================================================
PALETTE STYLE
==================================================

Use the existing Palette / Undertone system.

Desired direction:

slightly GOLD
+
very slightly GREEN / organic

This should produce:

- creamy highlights;
- warmer architectural neutrals;
- slightly olive greens;
- restrained blue/cyan;
- believable skin.

Suggested normalized starting point:

Palette:

x = -0.08
y = +0.22

Assuming existing axes:

X:
GREEN <-> ROSE

Y:
BLUE <-> GOLD

Keep this subtle.

==================================================
STYLE STRENGTH
==================================================

Initial preset strength:

approximately 0.70–0.80

Recommended initial value:

0.75

Users must still be able to reduce or increase Style strength.

At:

strength = 0

the result must return to NATURAL.

==================================================
BLOOM
==================================================

The reference does NOT contain strong obvious bloom.

If the current Style architecture includes Bloom:

use only a very subtle amount.

Suggested behavior:

Bloom:
enabled = true

Amount:
very low

Radius:
medium

Threshold:
high

Softness:
high

The purpose is only to make very bright regions slightly less digitally hard.

Do NOT create visible glow around every bright object.

If the effect becomes obvious:

reduce it.

A valid implementation may also leave Bloom disabled initially if visual tests show it is unnecessary.

==================================================
HALLATION
==================================================

Do NOT use strong hallation for this preset.

The reference does not show obvious red highlight halos.

Default recommendation:

Hallation:
OFF

or extremely subtle.

Do not manufacture a vintage-film effect that is not present in the reference.

==================================================
GRAIN
==================================================

Do NOT add film grain as part of this task.

The requested visual character should first be achieved through:

Tone
Color
Palette

not texture overlays.

Grain can be considered separately later.

==================================================
IMPLEMENTATION REQUIREMENTS
==================================================

Add the preset through the existing Style architecture.

Do NOT create an isolated image-filter pipeline specifically for this style.

Preferred architecture:

StylePreset
    ↓
StyleState
    ↓
StyleParameterResolver
    ↓
ToneStyleProcessor
    ↓
ColorStyleProcessor
    ↓
PaletteStyleProcessor
    ↓
optional Bloom
    ↓
output

The preset should only define parameters.

The rendering system should remain shared with other styles.

==================================================
PRESET MODEL
==================================================

Use the existing StylePreset model.

Conceptually:

data class StylePreset(
    val id: String,
    val name: String,
    val state: StyleState,
    val version: Int
)

Add:

id = "warm_street"

name = "Warm Street"

Do not hardcode rendering logic using:

if (style == "warm_street")

inside image-processing code.

The preset must work entirely through the generic StyleState / resolver system.

==================================================
VERSIONING
==================================================

Give this preset an explicit version.

Example:

warm_street-v1

or the equivalent existing project mechanism.

Future tuning must be versionable.

==================================================
NO MAGIC RGB FILTER
==================================================

Do NOT solve this style using:

red *= 1.1
green *= 1.05
blue *= 0.9

Do NOT use:

ColorMatrix(
    ...
)

as the main implementation.

Do NOT apply a warm overlay.

Do NOT implement:

image = blend(image, brownColor)

The intended look must emerge from controlled color science.

==================================================
PALETTE IMPLEMENTATION
==================================================

Prefer existing hue-aware transforms.

Potential operations include:

Hue vs Hue

Hue vs Saturation

Hue vs Luminance

bounded perceptual chroma adjustments

controlled warm/cool undertone

If the existing PaletteStyleProcessor cannot express this style cleanly, improve the generic processor instead of adding preset-specific hacks.

Any improvement must remain useful for other styles.

==================================================
SKIN SAFETY
==================================================

Do not detect faces or use beauty processing.

If the existing Style engine has hue-range skin protection, preserve plausible skin rendering.

The warm palette must not turn skin:

orange
red
pink
yellow

Keep skin believable.

==================================================
NEUTRAL SAFETY
==================================================

The style may warm neutral surfaces slightly, but should not destroy neutral relationships.

White wall:

neutral white
→ subtle warm cream

NOT:

yellow wall

Gray concrete:

neutral gray
→ slightly warm-neutral

NOT:

brown or green concrete

==================================================
SHADOW COLOR SAFETY
==================================================

Shadows may be subtly cooler than highlights.

Do NOT make shadows visibly:

cyan
teal
blue
green

The effect should only be perceptible as tonal separation.

==================================================
PREVIEW
==================================================

The live camera preview must use the same StyleState.

Do not create a completely different preview-only color recipe.

It is acceptable for the preview implementation to use a lower-resolution or GPU approximation, but the visual intent must match final capture.

==================================================
RAW
==================================================

RAW/DNG output remains untouched.

For:

RAW + Final

save:

RAW:
unmodified sensor-oriented DNG

Final:
NATURAL + Warm Street Style

Never bake this Style into RAW data.

==================================================
PURE / SYSTEM
==================================================

Follow STYLE_PLAN.md behavior.

Do not silently apply this Style to PURE or SYSTEM if those modes currently disable custom Style rendering.

Style is designed around the controlled NATURAL pipeline.

==================================================
VISUAL ACCEPTANCE TESTS
==================================================

Test the new preset using multiple real-world scenes.

1. Urban daylight

Look for:

- strong but not crushed shadows;
- creamy architecture;
- controlled sky;
- natural pavement;
- realistic signage colors.

2. Green foliage

Expected:

- organic green;
- slight olive character;
- no neon green;
- no yellow-green contamination.

3. Blue sky

Expected:

- natural blue;
- slightly restrained saturation;
- not cyan;
- not gray.

4. Human skin

Expected:

- natural;
- slightly warm at most;
- no orange/pink cast.

5. White / gray architecture

Expected:

- subtle creamy warmth;
- still recognizably neutral.

6. High dynamic range street scene

Expected:

- strong blacks;
- readable shadows;
- smooth highlights;
- no HDR flattening.

7. Indoor daylight

Expected:

- warm-neutral midtones;
- no heavy brown cast.

==================================================
REFERENCE COMPARISON
==================================================

When comparing to the supplied image, focus on:

1. tonal density;
2. shadow depth;
3. creamy highlights;
4. restrained blues;
5. organic greens;
6. warm-neutral architecture;
7. moderate color saturation;
8. absence of aggressive HDR.

Do NOT attempt pixel-perfect reproduction.

The purpose is to reproduce the photographic character, not the exact histogram of one reference image.

==================================================
DEBUG REQUIREMENTS
==================================================

In debug builds, expose or log:

preset ID
preset version

Tone X/Y
Color X/Y
Palette X/Y
Style Strength

resolved Tone parameters
resolved Color parameters
resolved Palette parameters

Bloom settings
Hallation settings

processing duration

Do not log image pixels.

==================================================
TESTS
==================================================

Add tests covering:

1. Preset registration.

2. Correct StyleState loading.

3. Preset version.

4. Style strength 0 returns NATURAL.

5. Coordinates stay within [-1, +1].

6. Tone curve remains monotonic.

7. Neutral-gray test does not produce severe color cast.

8. Blue hue remains valid and does not collapse into cyan.

9. Green hue remains bounded.

10. Skin-range hues remain plausible.

11. Final pixels remain finite and in valid output range.

==================================================
FILES
==================================================

Reuse existing Style architecture.

Do not create duplicate Style processing systems.

Before implementation, identify:

- StylePreset registry;
- StyleState;
- StyleParameterResolver;
- ToneStyleProcessor;
- ColorStyleProcessor;
- PaletteStyleProcessor;
- BloomProcessor if present;
- HallationProcessor if present;
- preview Style pipeline;
- final capture Style pipeline.

==================================================
IMPLEMENTATION PROCESS
==================================================

Before changing code, report:

1. where built-in Style presets currently live;
2. how StyleState is represented;
3. how Tone coordinates are mapped;
4. how Color coordinates are mapped;
5. how Palette coordinates are mapped;
6. whether Bloom/Hallation already exist;
7. whether preview and final capture currently share the same StyleState.

Then implement the preset with the smallest clean architectural change.

Do NOT rewrite the image pipeline.

==================================================
AFTER IMPLEMENTATION
==================================================

Report:

1. files changed;
2. preset ID/name/version;
3. StyleState values used;
4. resolved processing behavior;
5. whether generic Style engine changes were necessary;
6. tests added;
7. real-device validation still required;
8. any differences between preview and final output;
9. remaining visual tuning recommendations.

==================================================
FINAL RULE
==================================================

This preset should feel like:

warm
dense
muted
photographic
slightly filmic
urban
natural

It should NOT feel like:

filtered
sepia
orange/teal
HDR
overprocessed
retro gimmick

Build the look through the existing Style system, not through a one-off filter.