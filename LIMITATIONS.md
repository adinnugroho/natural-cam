# MVP Limitations & Device-Test Checklist

Everything here is an honest ceiling of the current implementation, not a
product decision. PRD intent is preserved via documented fallbacks (AGENTS 34).

## Known limitations

| # | Limitation | Cause | Upgrade path |
|---|-----------|-------|--------------|
| 1 | YUV stills come from the ImageAnalysis stream and may be below full sensor resolution | CameraX exposes no YUV photo stream on `ImageCapture` (see ADR-001) | Camera2 YUV capture session for the YUV path only |
| 2 | ~~Final JPEG keeps EXIF orientation instead of being physically rotated~~ **Resolved** | Output pixels are now physically rotated (SPEC §89); EXIF orientation is normal | — |
| 3 | AE/AF "lock" is a non-auto-cancelling metering action, not a true lens-position lock | CameraX exposes no lens lock API (SPEC §61 approximation) | Camera2 `CONTROL_AF_MODE` handling if users need true lock |
| 4 | Lens labels (0.5×/1×/3×) are derived from reported physical focal lengths | No platform API for marketing lens names | Per-device naming table in `compat/` if labels prove confusing |
| 5 | NATURAL/PURE tuning values (tone curve, denoise/sharpen strengths) are restrained starting points, not evaluated on devices | SPEC §130 intentionally simple first pipeline | Real-device evaluation per AGENTS 63, then tune `ProcessingConfiguration` |
| 6 | Compressed DNG (lossy JPEG) is rejected and falls back to the JPEG path | DngReader supports uncompressed CFA only | Extend reader or use CameraX-decoded pixels |
| 7 | Black/white level and color matrices come from the DNG tags; ActiveArea masking is ignored | Simplifies MVP decode; masked pixels are treated as image area | Honor `ActiveArea`/`DefaultCropOrigin` if edge artifacts appear |
| 8 | GPS tagging uses `LocationManager.getLastKnownLocation` (coarse, cached) | No fused provider without Play Services dependency | Acceptable for geotagging; revisit with user feedback |

## Verification status

### Host (dev machine)

- `gradle :app:testDebugUnitTest` — **63/63 tests green** (9 suites).
- `gradle :app:assembleDebug` — APK builds (`app/build/outputs/apk/debug/app-debug.apk`).

### Real device session — 2026-09-29 (SPEC §119 record)

| Field | Value |
|---|---|
| Device | Oppo CPH2737 (`OP5F02L1`) |
| OS | Android 16 (SDK 36), ColorOS `CPH2737_16.0.10.600(EX01)` |
| Camera | single back lens (`1×`), RAW_SENSOR + YUV + JPEG reported; flash + EV supported |
| Sensor | 4096×3072 RAW (16-bit, BGGR, black 64, white 1023, 3072 row-strips) |
| Test scene | indoor dim, ISO 1600, 0.03s, f/1.8 |

**Bugs found by this session and fixed** (all had unit regressions added):

| # | Bug | Symptom on device | Root cause |
|---|---|---|---|
| 1 | DNG decode abort | `Unsupported TIFF type 12` — first capture failed | reader rejected unneeded exotic tag types (DOUBLE etc.) instead of skipping |
| 2 | **All-black output** | every develop output mean 0 | Oppo ships a garbage `ForwardMatrix1` (count=1 zero) — reader padded it to a zero 3×3 that annihilated every pixel; now matrices require 9 valid non-singular elements and fall back to `ColorMatrix2` |
| 3 | Develop took 79s / GC thrash at 230MB heap | "Processing…" felt wedged | per-stage full-frame copies (146MB each at 12MP) + quadratic neighbor scans + per-pixel boxing in demosaic → in-place stages + O(1) CFA probes → **33–47s measured**, heap bounded |
| 4 | RAW/JPEG pair names mismatched | `…_301.dng` vs `…_000.jpg` | two timestamp sources (capture clock vs DNG second-precision) — one name clock now (SPEC §86) |
| 5 | SYSTEM + RAW modes wrong | RAW companion dropped in RAW+JPEG; `RAW and JPEG needs two output file options` crash-error in RAW only | stream format and save routing used different predicates — unified `wantsHalJpeg()` |
| 6 | Top control row untappable | Settings/Info/Timer/Grid dead to touches | targetSdk 35+ edge-to-edge: row rendered under status bar — `statusBarsPadding()` |
| 7 | Purple RAW whites | Latest Oppo DNG developed with magenta/blue whites | parser used AnalogBalance/BaselineExposure IDs as AsShotNeutral/ForwardMatrix1; corrected to DNG tags 50728/50964/50965 and added fallback WB |

**Verified working on device:** shutter→gallery JPEG (EXIF: ISO/exposure/f-number/datetime/make/model
present, orientation normal, 3072×4096 portrait from landscape sensor); all three profiles via UI
(`natural-v2` / `pure-v1` / `system-passthrough` in the SPEC §109 logs, outputs measurably different:
NATURAL mean [38,34,26] vs PURE [40,37,28] at stddev ≈ [32,30,23]); RAW+JPEG paired names; RAW only
outputs DNG alone; SYSTEM dual RAW+JPEG in one capture (captureMs ≈ 1350); EV slider (1/3-step
conversion); flash cycle; grid overlay (pixel-verified); tap-to-focus (no crash); settings navigation;
capture refusal/backpressure and typed errors with recovery; SPEC §109 logs (capture ID, plan,
timings).

**Measured performance (12 MP RAW develop, NATURAL):** prior single-threaded baseline was
~20.6s develop+save on the same Oppo device. After parallelizing independent full-resolution
ranges across the available CPU cores, the latest device capture completed in **13.7s**
(`captureMs=1978`, `processSaveMs=13704`, pipeline `natural-v7`). Scene and thermal state
were not controlled, so this is an indicative improvement, not a controlled benchmark.
NATURAL-v7 still disables full-frame denoise/sharpen by default; the known tradeoff is more
preserved grain and less artificial edge enhancement. Repeat the same scene and profile for
comparable timing.

**Not exercised (needs different hardware or more sessions):** lens switching (device has one
lens), front camera, flash photo in dark scene (flash cycle verified at UI level only), YUV
fallback path (device reports RAW so NATURAL never fell back), long-run memory soak, full-resolution
crop quality review by eye (AGENTS §61 — human inspection pending).

## Device-test checklist (AGENTS 59/80/81 — mandatory before calling camera work done)

Status from the 2026-09-29 session (Oppo CPH2737). Multi-vendor coverage (AGENTS §60) and
human image-quality review (AGENTS §61) remain open.

- [x] Preview starts quickly after permission; orientation correct (portrait UI, landscape sensor handled)
- [x] Shutter → gallery: JPEG appears in system gallery with correct orientation + EXIF
- [x] NATURAL on RAW-capable main: DNG saved in RAW modes; JPEG developed from RAW (vs SYSTEM HAL JPEG comparison recorded above)
- [x] PURE visibly less processed than SYSTEM — measured channel stats differ as expected (human crop review still pending)
- [ ] Ultrawide (often no RAW): NATURAL YUV fallback — **device has one lens; untested here**
- [ ] Front camera capture + mirror behavior — **no front lens exercised yet**
- [x] Tap-to-focus works without failure; EV slider honors the device 1/3 step (SPEC §57)
- [x] Flash OFF/AUTO/ON actually switches (dark-scene flash photo pending)
- [x] RAW toggle: RAW+JPEG pair shares base filename; RAW_ONLY produces only DNG
- [x] Quick repeated shutters: capture refused cleanly when busy (AGENTS 42), no crashes observed
- [ ] App kill mid-processing: no stale temp DNG after next launch — temp cleaner code present; live check pending
- [ ] Geotagging OFF → no GPS in EXIF; ON + permission → GPS present — **pending**
- [x] Capture/processing failures recover to a usable state (typed `SessionConfigurationFailed` on device, next capture succeeded)

## Processing-change log

| Date | What | Why | Profile | Expected effect | Downside | Devices | Comparison |
|---|---|---|---|---|---|---|---|
| 2026-09-26 | Output pixels physically rotated before encoding; EXIF orientation set to normal | SPEC §89 prefers physically correct output over EXIF orientation | ALL | Correct display in viewers that ignore EXIF | One pixel buffer copy on rotated shots | Oppo CPH2737 | orientation=1, 3072×4096 portrait verified |
| 2026-09-29 | Reject degenerate color matrices (require 9 finite non-singular elements; fall back ColorMatrix2) | Oppo CPH2737 ships a zero ForwardMatrix1 that blacked out every developed image | NATURAL, PURE | Correct color instead of black output | none known | Oppo CPH2737 | black (mean 0) → real image (mean [38,34,26]) |
| 2026-09-29 | In-place processing stages + O(1) CFA probes + allocation-free demosaic (math identical) | 12 MP develop hit 230MB/256MB heap GC thrash and quadratic scans | NATURAL, PURE | Same pixels, 79s → 33.9–47s develop | Stages now transfer buffer ownership (documented) | Oppo CPH2737 | output stddev unchanged ±0.2 across the change |
| 2026-09-30 | LUT tone/highlight/sRGB math, reusable blur buffers, callback-free CFA lookup, and NATURAL-v2 restrained defaults | 12 MP RAW develop remained too slow for point-and-shoot use | NATURAL | Same natural intent with less CPU and GC work | More visible grain; less edge enhancement | Oppo CPH2737 | 33.9–47s → ~20.6s measured |
| 2026-09-30 | Correct DNG metadata tag IDs and add metadata-missing working-RGB auto-WB fallback | Oppo's real DNG omitted the tags under the old IDs, producing purple/unstable whites | NATURAL, PURE | Neutral whites when metadata is present or absent | Fallback gray-world is an approximation when the camera omits WB metadata | Oppo CPH2737 | latest bright-neutral sample [253,249,254] vs previous magenta cast |
| 2026-09-30 | Select/interpolate DNG color matrices by CalibrationIlluminant + AsShotNeutral and lift NATURAL midtones with a sub-unity linear-light tone exponent | Daylight used the A-light matrix and the prior exponent suppressed midtones | NATURAL | Daylight neutrals use calibrated transforms; shadows/mid-gray remain readable while highlights retain the existing shoulder | Matrix interpolation needs multi-scene device review; no global exposure gain added | Oppo CPH2737 | street/indoor device samples reviewed; broader daylight chart comparison pending |
| 2026-10-01 | Apply a -0.3 EV capture bias for NATURAL while restoring the user's EV after capture | Latest RAW samples showed capture-level highlight clipping; post-processing cannot recover clipped sensor values | NATURAL | Fewer clipped highlights in comparable scenes | Shadows receive 0.3 stop less exposure; user EV remains authoritative | Oppo CPH2737 | DNG metadata confirmed 0.00999s → 0.00791s at ISO 160; scene brightness changed between samples |
| 2026-10-01 | Apply one conservative residual gray-world correction after the calibrated RAW transform when AsShotNeutral is present | PURE and NATURAL shared a visible neutral-surface cast after metadata WB; correction uses bright low-chroma samples, not fixed RGB multipliers | NATURAL, PURE | Neutral whites stay closer to neutral without changing YUV ISP-AWB output | Scene-wide gray-world can be biased by colored scenes; requires neutral-target validation | Oppo CPH2737 | same-scene PURE/NATURAL device captures; neutral-target chart pending |
| 2026-10-01 | Neutralize clipped near-white RAW highlights after WB/color transform | Latest PURE/NATURAL JPEG highlights were `[252,232,252]`: two channels clipped while green lagged, creating a false colored white | NATURAL, PURE | Clipped lights render neutral without desaturating single-color highlights | Irrecoverably clipped texture remains white; this corrects chromaticity, not detail | Oppo CPH2737 | PURE highlight chroma 28.0→6.4; NATURAL 25.6→6.2, median highlight `[255,255,255]` |
| 2026-10-01 | Preserve per-pixel RGB ratios through NATURAL tone/highlight mapping and enable 0.18-strength 2×2 chroma-only denoise | NATURAL color drifted from developed RAW and visible RGB speckle remained | NATURAL | Color stays closer to RAW development; chroma speckle is reduced while real luminance grain remains | Minimal 2×2 chroma filtering may show color blocking if strength is raised | Oppo CPH2737 | device JPEG inspected; flat dark-region chroma residual measured 0.00330 PURE vs 0.00265 NATURAL in available reference captures; luminance-preservation regression passes; no synthetic grain |
| 2026-10-02 | Reduce NATURAL highlight-rolloff compression from 0.50 to 0.25 | The previous shoulder made bright areas look too faded | NATURAL | More contrast and texture in bright midtones while retaining smooth highlight protection | More highlight clipping if exposure is excessive; requires real-device highlight review | Oppo CPH2737 | Pending same-scene comparison |
| 2026-10-02 | Parallelize independent RAW/YUV conversion, demosaic, color, tone, denoise, output, and rotation ranges across available CPU cores | Single-threaded full-resolution loops left the device CPU underused during NATURAL development | NATURAL, PURE | Lower develop latency without changing pixel math or image appearance | More CPU load and peak power while processing; same-scene device timing pending | Oppo CPH2737 | Device capture `captureMs=1978`, `processSaveMs=13704`; ~33% lower process+save time than the prior ~20.6s baseline; JPEG/DNG saved successfully |
| 2026-10-02 | Add a subtle NATURAL warmth bias (`warmth=0.01`) as a small linear red/blue balance | User requested a very slightly warmer rendering | NATURAL | Slightly warmer overall color without changing PURE or SYSTEM | May make already-warm scenes too warm; validate neutral surfaces and skin on device | Oppo CPH2737 | Pending same-scene device review |
| 2026-10-02 | Deepen NATURAL shadows by changing the toe exponent from 0.94 to 0.98 and reduce highlight compression from 0.25 to 0.10 | Shadows were lifted too much and the highlight shoulder still contributed a faded look | NATURAL | Deeper blacks with clearer highlight contrast and less washout | More shadow detail may be lost; bright clipping remains possible | Oppo CPH2737 | Pending same-scene device review |
| 2026-10-03 | Deepen NATURAL shadows with a 1.06 toe exponent, raise contrast to 0.04, and reduce highlight compression to 0.03 | Latest device JPG still showed lifted shadows and a strong faded highlight response | NATURAL | Deeper black floor and more decisive highlight contrast | Can hide shadow detail or clip bright areas sooner; requires latest-device review | Oppo CPH2737 | Latest pulled JPG `20261003_103340_831.jpg` reviewed; visual retest pending |
| 2026-10-03 | Add a 0.10-stop NATURAL lift while raising contrast to 0.05 and easing the toe to 1.04 | Latest rendering was slightly too dark; user requested a small brightness and contrast increase | NATURAL | Brighter image with modestly stronger separation while retaining deeper shadows | More highlight clipping headroom consumed; requires device review | Oppo CPH2737 | Unit tone regressions pending device capture |
| 2026-10-03 | Increase the NATURAL exposure lift from +0.10 to +0.30 EV | User requested a brighter final image | NATURAL | Noticeably brighter midtones while retaining the current contrast/shadow tuning | Reduced highlight headroom and possible shadow noise visibility | Oppo CPH2737 | Unit tone regressions pending device capture |
| 2026-10-03 | Add a NATURAL temperature slider with normalized cool/warm adjustment and a red/blue two-square control | User requested direct photo temperature control | NATURAL | Cool or warm the developed JPEG without changing PURE or SYSTEM | Temperature is a simple red/blue balance, not a full camera Kelvin model | Oppo CPH2737 | Unit pipeline regression pending device UI/capture review |
| 2026-10-04 | Reduce palette shadow tint from unit-scale to 0.08× and explicitly forward style preset actions through MainActivity | Latest styled JPEG had a severe blue/cyan cast; top-bar preset clicks crashed with AbstractMethodError | NATURAL | Restrained palette color character and stable preset selection | Palette directions are intentionally subtler; device retest required | Oppo CPH2737 | `20261004_122130_994.jpg` reviewed; crash log captured and fixed |
| 2026-10-04 | Make palette undertone chroma-weighted and reduce strength to 0.025× | Extreme upper-right style positions still produced a heavy yellow/orange cast | NATURAL | Palette direction remains visible without globally recoloring neutral shadows | Extreme palette colors are intentionally more restrained | Oppo CPH2737 | Latest bad-style JPEG reviewed; unit/device retest pending |
| 2026-10-04 | Bake a fixed +1.5 EV lift into the NATURAL recipe (`ToneConfig.exposureStops`), independent of the user EV control | User requested a brighter NATURAL baseline as part of the recipe | NATURAL | Brighter NATURAL output straight out of camera | Large lift; highlight headroom is reduced and the shoulder now does more work. PURE and SYSTEM pinned to 0 EV | Oppo CPH2737 | Device capture pending |
`natural-v16` and `pure-v2` are the current RAW pipeline versions.
