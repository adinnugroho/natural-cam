package com.adin.naturalcam.image

import com.adin.naturalcam.image.core.EncodedImage
import com.adin.naturalcam.image.core.JpegEncoder
import com.adin.naturalcam.image.core.RgbImage

/**
 * Shared deterministic test images and the recording encoder fake, so the image
 * tests do not each redefine an LCG, a flat field, or a JPEG stub. Mirrors
 * `raw/TestDngFactory`'s role for the RAW tests.
 */
internal object TestImages {

    /** Deterministic pseudo-random image in [0,1), independent channels (fixed LCG). */
    fun randomImage(size: Int = 64, seed: Long = 1234567L): RgbImage {
        val rgb = RgbImage(size, size)
        var state = seed
        for (i in rgb.r.indices) {
            state = (state * 1103515245 + 12345) and 0x7FFFFFFF
            rgb.r[i] = (state % 1000) / 1000f
            state = (state * 1103515245 + 12345) and 0x7FFFFFFF
            rgb.g[i] = (state % 1000) / 1000f
            state = (state * 1103515245 + 12345) and 0x7FFFFFFF
            rgb.b[i] = (state % 1000) / 1000f
        }
        return rgb
    }

    /** Flat image with the same level in all three channels. */
    fun uniform(size: Int = 256, value: Float = 0.30f): RgbImage {
        val rgb = RgbImage(size, size)
        for (i in rgb.r.indices) {
            rgb.r[i] = value
            rgb.g[i] = value
            rgb.b[i] = value
        }
        return rgb
    }

    /** Flat [base] plus per-pixel noise shared by all channels: value in [base-noise, base+noise). */
    fun noisyGray(size: Int, noise: Float, seed: Long, base: Float = 0.5f): RgbImage {
        val rgb = RgbImage(size, size)
        var state = seed
        for (i in rgb.r.indices) {
            state = (state * 1103515245 + 12345) and 0x7FFFFFFF
            val value = base + ((state % 2001) / 1000f - 1f) * noise
            rgb.r[i] = value
            rgb.g[i] = value
            rgb.b[i] = value
        }
        return rgb
    }

    /** Chroma checkerboard on a flat 0.5 luma: R and B swing by ±[swing], G stays 0.5. */
    fun checkerboard(size: Int, swing: Float = 0.2f): RgbImage {
        val rgb = RgbImage(size, size)
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            val s = if ((x + y) and 1 == 0) swing else -swing
            rgb.r[i] = 0.5f + s
            rgb.g[i] = 0.5f
            rgb.b[i] = 0.5f - s
        }
        return rgb
    }
}

/**
 * JpegEncoder fake that records the geometry, quality, and last ARGB frame of
 * each call. It keeps the pipeline's array by reference (nothing mutates it
 * after encode), so it adds nothing to a benchmark's allocation figure.
 */
internal class RecordingEncoder : JpegEncoder {
    var calls = 0
    var lastWidth = 0
    var lastHeight = 0
    var lastQuality = 0
    var argb: IntArray = IntArray(0)
        private set

    override fun encode(argb: IntArray, width: Int, height: Int, quality: Int): EncodedImage {
        calls++
        lastWidth = width
        lastHeight = height
        lastQuality = quality
        this.argb = argb
        return EncodedImage(ByteArray(16), width, height)
    }
}
