package com.adin.naturalcam.image.raw

import com.adin.naturalcam.image.core.RawImage

/**
 * Phase 3 bracketed RAW fusion. Frames are ordered by exposure product so the middle exposure
 * becomes the geometric reference; every frame is then exposure-normalized by the existing MFNR
 * aligner. Tone rendering stays in NATURAL and remains global/restrained.
 */
object RawBracketFusion {
    fun fuse(frames: List<RawImage>): RawBurstFusion.Result {
        require(frames.size >= 3) { "HDR bracket requires short, normal, and long RAW frames" }
        val referenceIndex = frames.indices
            .sortedBy { exposureProductOrDefault(frames[it]) }
            .let { it[it.size / 2] }
        val ordered = buildList {
            add(frames[referenceIndex])
            frames.indices.filter { it != referenceIndex }.forEach { add(frames[it]) }
        }
        val result = GlesRawBurstFusion.tryFuse(ordered) ?: RawBurstFusion.fuse(ordered)
        return result.copy(backend = "hdr-${result.backend}")
    }

    private fun exposureProductOrDefault(image: RawImage): Double {
        val exposure = image.metadata.exposureTimeNs ?: return 1.0
        val iso = image.metadata.isoSpeed ?: return 1.0
        return if (exposure > 0L && iso > 0) exposure.toDouble() * iso else 1.0
    }
}
