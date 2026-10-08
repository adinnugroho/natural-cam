package com.adin.naturalcam.image.raw

import com.adin.naturalcam.image.core.CfaLayout
import com.adin.naturalcam.image.core.LensShadingMap
import com.adin.naturalcam.image.core.RawCaptureMetadata
import com.adin.naturalcam.image.core.RawImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal DNG reader for the uncompressed CFA frames Android's DngCreator
 * produces (SPEC 31–32). Reads exactly the tags required to develop RAW
 * correctly; anything else (compressed DNG, tiled data, non-2x2 CFA) is
 * rejected loudly instead of producing a wrong image (AGENTS 18).
 *
 * Black levels are canonicalized to per-color (R, G, B) order regardless of
 * the DNG tag's per-pattern-position layout, matching RawNormalizer indexing
 * by CfaLayout.channelAt.
 */
object DngReader {

    class DngFormatException(message: String) : Exception(message)

    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_ORIENTATION = 274
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_DATE_TIME = 306
    private const val TAG_SUB_IFDS = 330
    private const val TAG_EXPOSURE_TIME = 33434
    private const val TAG_F_NUMBER = 33437
    private const val TAG_CFA_PATTERN_DIM = 33421
    private const val TAG_CFA_PATTERN = 33422
    private const val TAG_ISO = 34855
    private const val TAG_FOCAL_LENGTH = 37386
    private const val TAG_DATE_TIME_ORIGINAL = 36867
    private const val TAG_BLACK_LEVEL = 50714
    private const val TAG_WHITE_LEVEL = 50717
    private const val TAG_COLOR_MATRIX_1 = 50721
    private const val TAG_COLOR_MATRIX_2 = 50722
    private const val TAG_AS_SHOT_NEUTRAL = 50728
    private const val TAG_FORWARD_MATRIX_1 = 50964
    private const val TAG_FORWARD_MATRIX_2 = 50965
    private const val TAG_CALIBRATION_ILLUMINANT_1 = 50778
    private const val TAG_CALIBRATION_ILLUMINANT_2 = 50779
    private const val TAG_OPCODE_LIST_2 = 51009

    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_UNDEFINED = 7
    private const val TYPE_SSHORT = 8
    private const val TYPE_SLONG = 9
    private const val TYPE_SRATIONAL = 10
    private const val TYPE_FLOAT = 11
    private const val TYPE_DOUBLE = 12

    private const val CFA_PHOTOMETRIC = 32803L

    private const val MAX_OPCODES = 64
    private const val OPCODE_GAIN_MAP = 9

    /** Top/Left/Bottom/Right, Plane/Planes, RowPitch/ColPitch, MapPointsV/H — 10 uint32. */
    private const val GAIN_MAP_HEADER = 40
    private val OPCODE_ORDERS = arrayOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)

    fun read(file: File): RawImage = RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(8)
        raf.seek(0)
        raf.readFully(header)
        val order = when {
            header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> throw DngFormatException("Not a TIFF/DNG file")
        }
        val bb = ByteBuffer.wrap(header).order(order)
        if (bb.getShort(2) != 42.toShort()) throw DngFormatException("Bad TIFF magic")
        val ifd0Offset = bb.getInt(4).toLong() and 0xFFFFFFFFL

        val ifd0 = readIfd(raf, ifd0Offset, order)

        // Android DngCreator writes the CFA raw data into IFD0; other producers
        // may place it in a SubIFD. Metadata stays findable in either place.
        val rawIfd = if (ifd0.containsKey(TAG_STRIP_OFFSETS)) {
            ifd0
        } else {
            val sub = ifd0[TAG_SUB_IFDS]?.longValues()?.firstOrNull()
                ?: throw DngFormatException("No raw image data found")
            readIfd(raf, sub, order)
        }
        fun tag(n: Int): Entry? = rawIfd[n] ?: ifd0[n]

        val width = tag(TAG_IMAGE_WIDTH)?.longValue() ?: throw DngFormatException("Missing width")
        val height = tag(TAG_IMAGE_LENGTH)?.longValue() ?: throw DngFormatException("Missing height")
        if (width <= 0 || height <= 0 || width * height > 200_000_000) {
            throw DngFormatException("Unreasonable dimensions ${width}x$height")
        }
        val compression = tag(TAG_COMPRESSION)?.longValue() ?: 1
        if (compression != 1L) throw DngFormatException("Unsupported DNG compression $compression (only uncompressed)")
        val photometric = tag(TAG_PHOTOMETRIC)?.longValue() ?: CFA_PHOTOMETRIC
        if (photometric != CFA_PHOTOMETRIC) throw DngFormatException("Not a CFA DNG (photometric=$photometric)")
        val samples = tag(TAG_SAMPLES_PER_PIXEL)?.longValue() ?: 1
        if (samples != 1L) throw DngFormatException("Unsupported samplesPerPixel=$samples")
        val bits = tag(TAG_BITS_PER_SAMPLE)?.longValue() ?: 16
        if (bits != 16L && bits != 8L) throw DngFormatException("Unsupported bit depth $bits")

        val cfa = readCfa(tag(TAG_CFA_PATTERN), tag(TAG_CFA_PATTERN_DIM))

        val blackRaw = tag(TAG_BLACK_LEVEL)?.floatValues()
        val whiteLevel = tag(TAG_WHITE_LEVEL)?.longValue() ?: ((1L shl bits.toInt()) - 1)
        val blackPerColor = canonicalizeBlackLevel(blackRaw, cfa)

        val lensShading = tag(TAG_OPCODE_LIST_2)
            ?.byteValues()
            ?.let { parseLensShading(it, width.toInt(), height.toInt()) }

        val metadata = RawCaptureMetadata(
            cfa = cfa,
            blackLevelPerChannel = blackPerColor,
            whiteLevel = whiteLevel.toInt(),
            colorMatrix1 = tag(TAG_COLOR_MATRIX_1)?.matrixOrNull(),
            colorMatrix2 = tag(TAG_COLOR_MATRIX_2)?.matrixOrNull(),
            forwardMatrix1 = tag(TAG_FORWARD_MATRIX_1)?.matrixOrNull(),
            forwardMatrix2 = tag(TAG_FORWARD_MATRIX_2)?.matrixOrNull(),
            asShotNeutral = tag(TAG_AS_SHOT_NEUTRAL)?.takeIf { it.count == 3 }?.floatValues(),
            calibrationIlluminant1 = tag(TAG_CALIBRATION_ILLUMINANT_1)?.longValue()?.toInt(),
            calibrationIlluminant2 = tag(TAG_CALIBRATION_ILLUMINANT_2)?.longValue()?.toInt(),
            lensShading = lensShading,
            isoSpeed = tag(TAG_ISO)?.longValue()?.toInt(),
            exposureTimeNs = tag(TAG_EXPOSURE_TIME)?.rationalValue()?.let { (it * 1_000_000_000.0).toLong() },
            aperture = tag(TAG_F_NUMBER)?.rationalValue()?.toFloat(),
            focalLengthMm = tag(TAG_FOCAL_LENGTH)?.rationalValue()?.toFloat(),
            orientationDegrees = orientationDegrees(tag(TAG_ORIENTATION)?.longValue() ?: 1),
            timestampMs = parseTimestamp(tag(TAG_DATE_TIME_ORIGINAL)?.asciiValue() ?: tag(TAG_DATE_TIME)?.asciiValue())
                ?: System.currentTimeMillis(),
        )

        val pixels = readPixels(raf, rawIfd, order, width, height, bits)
        RawImage(width.toInt(), height.toInt(), pixels, metadata)
    }

    /** Matrices must be exactly 9 elements — real DNGs carry garbage count=1 tags (Oppo CPH2737). */
    private fun Entry.matrixOrNull(): FloatArray? =
        if (count == 9) floatValues() else null

    /** Black level as per-color (R,G,B); size-1 tags replicate, size-4 tags map via CFA positions. */
    private fun canonicalizeBlackLevel(values: FloatArray?, cfa: CfaLayout): FloatArray = when {
        values == null || values.isEmpty() -> floatArrayOf(0f)
        values.size == 1 -> floatArrayOf(values[0], values[0], values[0])
        values.size == 3 -> values.copyOf()
        values.size == 4 -> {
            val perColor = FloatArray(3)
            val count = IntArray(3)
            for (y in 0..1) for (x in 0..1) {
                val ch = cfa.channelAt(x, y)
                perColor[ch] += values[y * 2 + x]
                count[ch]++
            }
            FloatArray(3) { i -> if (count[i] == 0) 0f else perColor[i] / count[i] }
        }
        else -> throw DngFormatException("Unsupported BlackLevel count ${values.size}")
    }

    private fun readCfa(pattern: Entry?, dim: Entry?): CfaLayout {
        val d = dim?.longValues()
        if (d != null && (d[0] != 2L || d[1] != 2L)) throw DngFormatException("Unsupported CFA repeat dim ${d[0]}x${d[1]}")
        val p = pattern?.byteValues() ?: throw DngFormatException("Missing CFAPattern")
        if (p.size < 4) throw DngFormatException("Short CFAPattern")
        // DNG CFAPattern is row-major: (0,0),(1,0),(0,1),(1,1); values 0=R,1=G,2=B.
        val key = p[0].toInt() * 1000 + p[1].toInt() * 100 + p[2].toInt() * 10 + p[3].toInt()
        return when (key) {
            112 -> CfaLayout.RGGB // 0,1,1,2
            1021 -> CfaLayout.GRBG
            1201 -> CfaLayout.GBRG
            2110 -> CfaLayout.BGGR
            else -> throw DngFormatException("Unsupported CFA pattern $key")
        }
    }

    /**
     * Extracts the GainMap lens-shading grids from an OpcodeList2 payload (DNG
     * 1.4 opcode 9, SPEC 31). Only single-plane maps that cover the whole frame
     * are consumed; every other opcode, plane layout, or partial region is
     * skipped, so the developer falls back to no correction instead of applying
     * a map to the wrong pixels (AGENTS 18).
     *
     * The byte order is detected rather than assumed from the TIFF header: some
     * HALs (Oppo CPH2737) write the opcode payload big-endian inside a
     * little-endian DNG.
     */
    internal fun parseLensShading(bytes: ByteArray, width: Int, height: Int): LensShadingMap? {
        val order = opcodeOrder(bytes) ?: return null
        val bb = ByteBuffer.wrap(bytes).order(order)
        val count = bb.getInt(0)
        val grids = arrayOfNulls<FloatArray>(4)
        var columns = 0
        var rows = 0
        var pos = 4
        for (i in 0 until count) {
            if (pos + 16 > bytes.size) break
            val id = bb.getInt(pos)
            val paramSize = bb.getInt(pos + 12)
            pos += 16
            if (paramSize < 0 || pos + paramSize > bytes.size) break
            if (id == OPCODE_GAIN_MAP) {
                val grid = gainGrid(bb, pos, paramSize, width, height)
                if (grid != null && grids[grid.key] == null &&
                    (columns == 0 || (grid.columns == columns && grid.rows == rows))
                ) {
                    grids[grid.key] = grid.values
                    columns = grid.columns
                    rows = grid.rows
                }
            }
            pos += paramSize
        }
        if (columns == 0) return null
        return LensShadingMap(columns, rows, width, height, grids)
    }

    private class GainGrid(val key: Int, val columns: Int, val rows: Int, val values: FloatArray)

    /** The grid is stored at the end of the parameter block, so its size locates it. */
    private fun gainGrid(bb: ByteBuffer, at: Int, paramSize: Int, width: Int, height: Int): GainGrid? {
        if (paramSize < GAIN_MAP_HEADER + 4) return null
        val top = bb.getInt(at)
        val left = bb.getInt(at + 4)
        val bottom = bb.getInt(at + 8)
        val right = bb.getInt(at + 12)
        val plane = bb.getInt(at + 16)
        val planes = bb.getInt(at + 20)
        val rowPitch = bb.getInt(at + 24)
        val colPitch = bb.getInt(at + 28)
        val mapRows = bb.getInt(at + 32)
        val mapColumns = bb.getInt(at + 36)
        if (planes != 1 || plane != 0 || rowPitch <= 0 || colPitch <= 0) return null
        if (mapRows <= 1 || mapColumns <= 1) return null
        val cells = mapRows.toLong() * mapColumns
        if (cells * 4 > paramSize - GAIN_MAP_HEADER) return null
        // A partial-region map needs spatial remapping this developer does not do.
        if (top > 1 || left > 1 || bottom < height || right < width) return null
        val dataAt = at + paramSize - (cells * 4).toInt()
        val values = FloatArray(cells.toInt())
        for (k in values.indices) values[k] = bb.getFloat(dataAt + k * 4)
        return GainGrid((top and 1) * 2 + (left and 1), mapColumns, mapRows, values)
    }

    private fun opcodeOrder(bytes: ByteArray): ByteOrder? {
        if (bytes.size < 4) return null
        for (order in OPCODE_ORDERS) {
            val bb = ByteBuffer.wrap(bytes).order(order)
            val count = bb.getInt(0)
            if (count <= 0 || count > MAX_OPCODES) continue
            var pos = 4
            var ok = true
            for (i in 0 until count) {
                if (pos + 16 > bytes.size) { ok = false; break }
                val size = bb.getInt(pos + 12)
                if (size < 0 || pos + 16 + size > bytes.size) { ok = false; break }
                pos += 16 + size
            }
            if (ok) return order
        }
        return null
    }

    private fun orientationDegrees(tiff: Long): Int = when (tiff) {
        3L -> 180
        6L -> 90
        8L -> 270
        else -> 0
    }

    private fun parseTimestamp(text: String?): Long? {
        if (text == null) return null
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(text.trim())?.time
        }.getOrNull()
    }

    private fun readPixels(
        raf: RandomAccessFile,
        ifd: Map<Int, Entry>,
        order: ByteOrder,
        width: Long,
        height: Long,
        bits: Long,
    ): ShortArray {
        val offsets = ifd[TAG_STRIP_OFFSETS]?.longValues() ?: longArrayOf()
        val counts = ifd[TAG_STRIP_BYTE_COUNTS]?.longValues() ?: longArrayOf()
        if (offsets.isEmpty() || counts.isEmpty() || offsets.size != counts.size) {
            throw DngFormatException("Bad strip layout")
        }
        val total = (width * height).toInt()
        val out = ShortArray(total)
        var written = 0
        val bytesPerPixel = (bits / 8).toInt()
        for (i in offsets.indices) {
            val byteCount = counts[i].toInt()
            val buf = ByteArray(byteCount)
            raf.seek(offsets[i])
            raf.readFully(buf)
            val bb = ByteBuffer.wrap(buf).order(order)
            val n = byteCount / bytesPerPixel
            val take = minOf(n, total - written)
            if (take <= 0) break
            if (bytesPerPixel == 2) {
                // Bulk copy: the per-short loop this replaces dominated the decode.
                bb.asShortBuffer().get(out, written, take)
                written += take
            } else {
                val bytes = bb.array()
                for (j in 0 until take) out[written++] = (bytes[j].toInt() and 0xFF).toShort()
            }
        }
        if (written != total) throw DngFormatException("Pixel data short: $written != $total")
        return out
    }

    // ---- TIFF plumbing -------------------------------------------------

    private class Entry(val type: Int, val count: Int, private val valueBytes: ByteArray, private val order: ByteOrder) {
        private fun buffer(): ByteBuffer = ByteBuffer.wrap(valueBytes).order(order)

        fun longValue(): Long? = longValues().firstOrNull()

        fun longValues(): LongArray = when (type) {
            TYPE_BYTE, TYPE_UNDEFINED -> LongArray(count) { valueBytes[it].toLong() and 0xFF }
            TYPE_SHORT -> LongArray(count) { buffer().getShort(it * 2).toLong() and 0xFFFF }
            TYPE_SSHORT -> LongArray(count) { buffer().getShort(it * 2).toLong() }
            TYPE_LONG -> LongArray(count) { buffer().getInt(it * 4).toLong() and 0xFFFFFFFFL }
            TYPE_SLONG -> LongArray(count) { buffer().getInt(it * 4).toLong() }
            else -> longArrayOf()
        }

        fun byteValues(): ByteArray = if (type == TYPE_BYTE || type == TYPE_UNDEFINED || type == TYPE_ASCII) valueBytes else byteArrayOf()

        fun asciiValue(): String? = if (type == TYPE_ASCII) {
            valueBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII).ifBlank { null }
        } else {
            null
        }

        fun floatValues(): FloatArray? = when (type) {
            TYPE_FLOAT -> FloatArray(count) { buffer().getFloat(it * 4) }
            TYPE_DOUBLE -> FloatArray(count) { buffer().getDouble(it * 8).toFloat() }
            TYPE_SHORT, TYPE_SSHORT, TYPE_LONG, TYPE_SLONG, TYPE_BYTE -> longValues().map { it.toFloat() }.toFloatArray()
            TYPE_RATIONAL -> FloatArray(count) {
                val b = buffer()
                val num = b.getInt(it * 8)
                val den = b.getInt(it * 8 + 4)
                if (den == 0) 0f else num.toFloat() / den
            }
            TYPE_SRATIONAL -> FloatArray(count) {
                val b = buffer()
                val num = b.getInt(it * 8)
                val den = b.getInt(it * 8 + 4)
                if (den == 0) 0f else num.toFloat() / den
            }
            else -> null
        }

        fun rationalValue(): Double? = floatValues()?.firstOrNull()?.toDouble()
    }

    private fun readIfd(raf: RandomAccessFile, offset: Long, order: ByteOrder): Map<Int, Entry> {
        raf.seek(offset)
        val countBuf = ByteArray(2)
        raf.readFully(countBuf)
        val count = ByteBuffer.wrap(countBuf).order(order).getShort().toInt() and 0xFFFF
        val entries = ByteArray(count * 12)
        raf.readFully(entries)
        val bb = ByteBuffer.wrap(entries).order(order)
        val map = HashMap<Int, Entry>(count)
        for (i in 0 until count) {
            val base = i * 12
            val tag = bb.getShort(base).toInt() and 0xFFFF
            val type = bb.getShort(base + 2).toInt() and 0xFFFF
            val valueCount = bb.getInt(base + 4)
            // Real producers (e.g. Oppo CPH2737 DNG) use exotic TIFF types we do not
            // consume; skip unknown tags instead of failing the whole decode.
            val unitSize = typeSize(type) ?: continue
            val valueSize = unitSize * valueCount
            val stored = ByteArray(4)
            for (k in 0..3) stored[k] = bb.get(base + 8 + k)
            val valueBytes = if (valueSize <= 4) {
                stored.copyOf(valueSize)
            } else {
                val at = ByteBuffer.wrap(stored).order(order).getInt().toLong() and 0xFFFFFFFFL
                val out = ByteArray(valueSize)
                raf.seek(at)
                raf.readFully(out)
                out
            }
            map[tag] = Entry(type, valueCount, valueBytes, order)
        }
        return map
    }

    private fun typeSize(type: Int): Int? = when (type) {
        TYPE_BYTE, TYPE_ASCII, TYPE_UNDEFINED -> 1
        TYPE_SHORT, TYPE_SSHORT -> 2
        TYPE_LONG, TYPE_SLONG, TYPE_FLOAT -> 4
        TYPE_RATIONAL, TYPE_SRATIONAL, TYPE_DOUBLE -> 8
        else -> null // unknown tag type: skip the entry, keep decoding
    }
}
