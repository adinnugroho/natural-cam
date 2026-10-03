# ADR-001: Camera backend — CameraX 1.6.2 first, Camera2 deferred

Status: accepted (MVP)

## Context

SPEC §9 prefers CameraX when it does not block PRD requirements, and allows
direct Camera2 where RAW/YUV stream configurations or capture metadata are
otherwise unavailable. The PRD requires:

1. RAW capture with the metadata needed to develop it correctly (AGENTS 18);
2. a YUV fallback for NATURAL on cameras without RAW (PRD §34 fallback chain);
3. per-camera capability detection from real metadata (AGENTS 9).

## Decision

Use a single CameraX backend (`CameraXController`) with Camera2 interop
(`Camera2CameraInfo`) for capability scanning.

- **RAW / RAW+JPEG**: `ImageCapture` output formats `OUTPUT_FORMAT_RAW` and
  `OUTPUT_FORMAT_RAW_JPEG` (CameraX 1.6.2). CameraX writes the DNG through
  `DngCreator` with full `CaptureResult` metadata; `DngReader` then parses the
  saved DNG for custom development. RAW+JPEG single-shot is only used for
  SYSTEM + RAW mode; NATURAL/PURE develop the JPEG from the RAW themselves.
- **YUV stills**: `ImageAnalysis` stream, capturing the next analyzed frame at
  the stream's configured resolution (highest the device binds).
- **Preview / focus / EV / flash**: CameraX `Preview`, `CameraControl`,
  `PreviewView.meteringPointFactory` (the reliable metering abstraction —
  SPEC §60 forbids approximating it ourselves).

## Alternatives considered

- **Full Camera2 backend for everything**: gives native RAW_SENSOR+JPEG+YUV
  streams with `CaptureResult` in hand, but duplicates preview/session/3A
  handling that CameraX already stabilizes. Rejected for MVP complexity and
  camera-session risk (AGENTS §68: prefer incremental improvement).
- **CameraX before 1.6.2**: no RAW output format at all (verified against the
  1.4.1 artifact). Rejected.

## Trade-offs

- YUV stills inherit the ImageAnalysis stream resolution, which may be below
  full sensor resolution on some devices. Documented in LIMITATIONS.md; the
  upgrade path is a Camera2 capture session for the YUV path only.
- `PreviewView` appears in the `CameraController` interface for surface
  provisioning and metering points. Acceptable: it is a view object; no
  session, use case or `CaptureRequest.Builder` leaks into UI code (AGENTS 15).
- RAW development reads the DNG produced by the capture instead of raw
  `ImageProxy` planes. Costs one file write; buys complete, correct metadata.

## Consequences

- One backend to maintain for MVP; the `CameraController` interface keeps a
  future `Camera2Backend` possible without UI changes (SPEC §8).
- RAW+JPEG dual capture callback semantics are handled by content readiness
  (file/stream non-empty) rather than callback count.
