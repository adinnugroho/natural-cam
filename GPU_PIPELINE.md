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
- AsShotNeutral + camera matrix required
- Style workspace states fall back to CPU (not yet ported)
- CPU fallback for unsupported devices

## Implementation

- `GpuRawPipeline.kt` — persistent EGL session, 3-pass shaders
- `GpuPipelineConfig` — config data class
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
