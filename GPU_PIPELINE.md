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

## Current status (2026-10-26)

- GPU path activates: `gpuPipelineBackend=gpu-native-natural`
- GPU time: 24.7s (Oppo CPH2737)
- CPU reference: ~13s for same scene
- **GPU is currently slower than CPU** — single mega-shader with 15+ texture
  fetches per pixel (demosaic probes + 2x2 denoise + 3x3 sharpen) is too
  expensive on this Mali GPU.

## Required optimization

Split into multi-pass pipeline:
1. Pass 1: normalize + demosaic + WB + color matrix → RGBA16F
2. Pass 2: NATURAL tone + highlight roll-off → RGBA16F
3. Pass 3: denoise + sharpen → RGBA8

Each pass has fewer texture fetches, better cache utilization, and the GPU
can overlap passes.

## Supported contract

- PURE and NATURAL profiles
- AsShotNeutral + camera matrix required
- Style workspace states fall back to CPU (not yet ported)
- CPU fallback for unsupported devices

## Implementation

- `GpuRawPipeline.kt` — persistent EGL session, single-pass shader
- `GpuPipelineConfig` — config data class
- `ImagePipeline.processRaw` — routes to GPU behind `EXPERIMENTAL_GPU_PIPELINE`

## Performance history

| Date | Approach | GPU time | CPU time |
|---|---|---|---|
| 2026-10-25 | RGBA32F readback between stages | 12.2s | 1.2s |
| 2026-10-26 | GPU-native single-pass, 8-bit readback only | 24.7s | ~13s |
| Target | Multi-pass, 8-bit readback only | <3s | ~13s |
