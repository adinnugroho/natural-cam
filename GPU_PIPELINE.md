# GPU-Native Pipeline Architecture

## Goal

Replace the CPU reference pipeline with a GPU-native pipeline that keeps
frame data in GPU memory across all stages, matching PhotonCamera's
approach. Only the final 8-bit JPEG crosses the GPU/CPU boundary.

## Architecture

```text
RAW 16-bit upload (once)
    ↓
GPU shader: normalize + demosaic + WB + color matrix
           + NATURAL tone + highlight roll-off
           + denoise + sharpen + clipped-neutralize
    ↓
GPU output: RGBA8 texture
    ↓
Read back final 8-bit (only readback)
    ↓
CPU: rotation + crop + JPEG encode
```

## Key principle

No full-resolution float readback between stages. The previous GPU
prototype was slow (12.2s vs 1.2s CPU) because it read the full
12MP RGBA32F frame back to Kotlin. This new architecture keeps everything
as GPU textures.

## Current status (2026-10-26, multi-pass)

- GPU path activates: `gpuPipelineBackend=gpu-native-natural`
- **Multi-pass GPU time: 6.2s** (Oppo CPH2737)
- CPU reference: ~13s for same scene
- **GPU is 2x faster than CPU**
- Total capture: 17.1s (DNG read 10.8s + fusion 6.9s + GPU 6.2s + style/grain/save)
- Remaining bottleneck: DNG decode + RAW fusion, not the GPU pipeline

## Supported contract

- PURE and NATURAL profiles
- Any style workspace state (pads, saturation, bloom, grain)
- AsShotNeutral + camera matrix required
- CPU fallback when either is missing

## Style chain on GPU

Ported from `StyleEngine` so a styled capture no longer falls back to CPU:

| Stage | Where | Notes |
|---|---|---|
| Style denoise | pass 3 chroma strength | `STYLE_CHROMA_DENOISE_STRENGTH * strength`, plus `SATURATION_CHROMA_DENOISE * saturation` when boosted, folded into the existing denoise |
| Palette | pass 3 | R/B chroma cross-feed, luma-weighted shadow tint, chroma boost factor |
| Color | pass 3 | Chroma scale, soft-knee compression, chroma-weighted temperature |
| Tone | pass 3 | Shadow-only lift, 0.18-pivot contrast, shoulder above 0.7 |
| Saturation | pass 3 | Signed chroma scale about the pixel's luma |
| Bloom | own passes | Peak mask at 1/16, six 3×3 blur passes, half-texel bilinear upsample in composite |
| Grain | CPU after readback | Belongs to the delivered pixels; a whole-level shift on all channels is colour-free |

Order on GPU is palette → color → tone → saturation, matching `StyleEngine.apply`.
Palette/color/tone/saturation were verified numerically against the CPU
implementation across 12k samples and three style states (max difference 0.0);
bloom matches to 4.6e-3, which is RGBA16F storage precision over six blur passes.

Not yet ported: the grain estimate that makes CPU denoise/sharpen strengths
adaptive. GPU uses the configured strengths, so a GPU frame is slightly less
adaptive than the CPU reference on noisy frames.

## Implementation

- `GpuRawPipeline.kt` — persistent EGL session, 3 passes + bloom + composite
- `GpuPipelineConfig` — config data class, carries the resolved style
- `ImagePipeline.processRaw` — routes to GPU behind `EXPERIMENTAL_GPU_PIPELINE`

## Performance history

| Date | Approach | GPU time | CPU time |
|---|---|---|---|
| 2026-10-25 | RGBA32F readback between stages | 12.2s | 1.2s |
| 2026-10-26 | GPU-native single-pass, 8-bit readback only | 24.7s | ~13s |
| 2026-10-26 | **Multi-pass, 8-bit readback only** | **6.2s** | **~13s** |

## Remaining bottleneck

DNG decode (10.8s) and RAW fusion (6.9s) now dominate. The GPU pipeline
itself is fast. Next optimization: memory-mapped DNG decode.
