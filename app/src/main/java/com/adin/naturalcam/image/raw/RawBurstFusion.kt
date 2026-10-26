package com.adin.naturalcam.image.raw

import com.adin.naturalcam.image.core.RawImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Small CPU reference implementation for same-exposure RAW MFNR.
 *
 * It aligns a reduced green-channel proxy with a bounded translation search, then averages
 * matching CFA samples after exposure normalization. This is deliberately not the final GPU
 * stacker: it establishes numerical behavior and a safe fallback before optimization.
 */
object RawBurstFusion {
    private const val PROXY_STEP = 4
    private const val MAX_SHIFT_CELLS = 3
    private const val PROXY_SAMPLE_STEP = 2
    private const val MAX_ALIGNMENT_RMSE = 0.05f
    private const val MAX_EXPOSURE_SCALE = 4.0f

    data class Result(
        val image: RawImage,
        val mergedFrameCount: Int,
        val skippedFrameCount: Int,
        val alignmentRmse: List<Float>,
        val backend: String = "cpu",
    )

    private data class Proxy(val width: Int, val height: Int, val values: FloatArray)
    private data class Alignment(val dx: Int, val dy: Int, val rmse: Float)


    fun fuse(frames: List<RawImage>): Result {
        require(frames.isNotEmpty()) { "RAW burst must contain at least one frame" }
        if (frames.size == 1) {
            return Result(frames[0], mergedFrameCount = 1, skippedFrameCount = 0, alignmentRmse = emptyList())
        }

        val reference = frames.first()
        val referenceProxy = buildProxy(reference, exposureScale = 1f)
        val sums = FloatArray(reference.pixelData.size) { index ->
            rawInReferenceDomain(reference, reference, index)
        }
        val counts = ByteArray(reference.pixelData.size) { 1 }
        val errors = ArrayList<Float>(frames.size - 1)
        var merged = 1
        var skipped = 0

        for (frame in frames.drop(1)) {
            if (!compatible(reference, frame)) {
                skipped++
                continue
            }
            val scale = exposureScale(reference, frame) ?: run {
                skipped++
                continue
            }
            val alignment = findAlignment(referenceProxy, buildProxy(frame, scale))
            errors += alignment.rmse
            if (alignment.rmse > MAX_ALIGNMENT_RMSE) {
                skipped++
                continue
            }

            for (y in 0 until reference.height) {
                val sourceY = y + alignment.dy * PROXY_STEP
                if (sourceY !in 0 until frame.height) continue
                for (x in 0 until reference.width) {
                    val sourceX = x + alignment.dx * PROXY_STEP
                    if (sourceX !in 0 until frame.width) continue
                    val outputIndex = y * reference.width + x
                    val sourceIndex = sourceY * frame.width + sourceX
                    sums[outputIndex] += rawInReferenceDomain(reference, frame, sourceIndex, scale)
                    counts[outputIndex] = (counts[outputIndex].toInt() + 1).toByte()
                }
            }
            merged++
        }

        val output = ShortArray(reference.pixelData.size)
        for (index in output.indices) {
            val count = counts[index].toInt()
            output[index] = sums[index]
                .div(count)
                .roundToInt()
                .coerceIn(0, reference.metadata.whiteLevel)
                .toShort()
        }
        return Result(
            image = RawImage(reference.width, reference.height, output, reference.metadata),
            mergedFrameCount = merged,
            skippedFrameCount = skipped,
            alignmentRmse = errors,
        )
    }

    private fun compatible(reference: RawImage, frame: RawImage): Boolean =
        reference.width == frame.width &&
            reference.height == frame.height &&
            reference.metadata.cfa == frame.metadata.cfa &&
            reference.metadata.whiteLevel == frame.metadata.whiteLevel

    private fun exposureScale(reference: RawImage, frame: RawImage): Float? {
        val referenceProduct = exposureProduct(reference)
        val frameProduct = exposureProduct(frame)
        if (referenceProduct == null || frameProduct == null) return 1f
        val scale = referenceProduct / frameProduct
        return scale.toFloat().takeIf { it.isFinite() && it > 0f && it <= MAX_EXPOSURE_SCALE && it >= 1f / MAX_EXPOSURE_SCALE }
    }

    private fun exposureProduct(image: RawImage): Double? {
        val exposure = image.metadata.exposureTimeNs ?: return null
        val iso = image.metadata.isoSpeed ?: return null
        if (exposure <= 0L || iso <= 0) return null
        return exposure.toDouble() * iso
    }

    private fun buildProxy(image: RawImage, exposureScale: Float): Proxy {
        val width = (image.width + PROXY_STEP - 1) / PROXY_STEP
        val height = (image.height + PROXY_STEP - 1) / PROXY_STEP
        val values = FloatArray(width * height)
        for (proxyY in 0 until height) {
            val top = proxyY * PROXY_STEP
            val bottom = min(top + PROXY_STEP, image.height)
            for (proxyX in 0 until width) {
                val left = proxyX * PROXY_STEP
                val right = min(left + PROXY_STEP, image.width)
                var sum = 0f
                var count = 0
                for (y in top until bottom) {
                    for (x in left until right) {
                        if (image.metadata.cfa.channelAt(x, y) != 1) continue
                        val index = y * image.width + x
                        sum += normalizedSignal(image, index) * exposureScale
                        count++
                    }
                }
                values[proxyY * width + proxyX] = if (count == 0) 0f else sum / count
            }
        }
        return Proxy(width, height, values)
    }

    private fun findAlignment(reference: Proxy, current: Proxy): Alignment {
        var best = Alignment(0, 0, Float.POSITIVE_INFINITY)
        for (dy in -MAX_SHIFT_CELLS..MAX_SHIFT_CELLS) {
            for (dx in -MAX_SHIFT_CELLS..MAX_SHIFT_CELLS) {
                var sum = 0.0
                var count = 0
                val firstX = max(0, -dx)
                val lastX = min(reference.width, reference.width - dx)
                val firstY = max(0, -dy)
                val lastY = min(reference.height, reference.height - dy)
                var y = firstY
                while (y < lastY) {
                    var x = firstX
                    while (x < lastX) {
                        val referenceValue = reference.values[y * reference.width + x]
                        val currentValue = current.values[(y + dy) * current.width + x + dx]
                        val difference = referenceValue - currentValue
                        sum += difference.toDouble() * difference
                        count++
                        x += PROXY_SAMPLE_STEP
                    }
                    y += PROXY_SAMPLE_STEP
                }
                if (count == 0) continue
                val rmse = sqrt((sum / count).toFloat())
                if (
                    rmse < best.rmse - 1e-6f ||
                    (abs(rmse - best.rmse) <= 1e-6f &&
                        abs(dx) + abs(dy) < abs(best.dx) + abs(best.dy))
                ) {
                    best = Alignment(dx, dy, rmse)
                }
            }
        }
        return best
    }

    private fun rawInReferenceDomain(
        reference: RawImage,
        frame: RawImage,
        index: Int,
        scale: Float = 1f,
    ): Float {
        val x = index % frame.width
        val y = index / frame.width
        val channel = frame.metadata.cfa.channelAt(x, y)
        val frameBlack = frame.metadata.blackLevelPerChannel[channel.coerceAtMost(frame.metadata.blackLevelPerChannel.lastIndex)]
        val referenceBlack = reference.metadata.blackLevelPerChannel[channel.coerceAtMost(reference.metadata.blackLevelPerChannel.lastIndex)]
        val sample = (frame.pixelData[index].toInt() and 0xffff) - frameBlack
        return referenceBlack + max(0f, sample) * scale
    }

    private fun normalizedSignal(image: RawImage, index: Int): Float {
        val x = index % image.width
        val y = index / image.width
        val channel = image.metadata.cfa.channelAt(x, y)
        val black = image.metadata.blackLevelPerChannel[channel.coerceAtMost(image.metadata.blackLevelPerChannel.lastIndex)]
        val range = (image.metadata.whiteLevel - black).coerceAtLeast(1f)
        return (((image.pixelData[index].toInt() and 0xffff) - black) / range).coerceIn(0f, 1f)
    }
}
