package com.adin.naturalcam.image.raw

import com.adin.naturalcam.image.core.CfaLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test-only writer for minimal uncompressed 16-bit CFA DNG files (same tag
 * subset DngReader consumes). Shared by DngReaderTest and CaptureCoordinatorTest.
 */
internal object TestDngFactory {

    fun buildDng(
        cfa: CfaLayout,
        blackLevel: List<Pair<Int, Int>> = listOf(64 to 1, 64 to 1, 64 to 1, 64 to 1),
        whiteLevel: Int = 4095,
        width: Int = 4,
        height: Int = 4,
        pixelValue: Int = 1000,
        withExoticTags: Boolean = false,
    ): File {
        val t = TiffBuilder(width, height)
        t.addLong(256, width.toLong())
        t.addLong(257, height.toLong())
        t.addShort(258, 16)
        t.addShort(259, 1)
        t.addShort(262, 32803)
        t.addShort(274, 1)
        t.addShort(277, 1)
        t.addLong(278, height.toLong())
        val pixels = ByteArray(width * height * 2)
        val bb = ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN)
        repeat(width * height) { bb.putShort(pixelValue.toShort()) }
        t.addLong(279, pixels.size.toLong())
        t.addShort(33421, 2, 2)
        t.addBytes(33422, cfaPattern(cfa))
        t.addRational(50714, blackLevel)
        t.addShort(50717, whiteLevel)
        t.addSrational(50721, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
        t.addSrational(50728, floatArrayOf(1f, 1f, 1f))
        if (withExoticTags) {
            // Real-world producers emit tag types we do not consume: a DOUBLE tag
            // (seen in Oppo CPH2737 DNGs) and a completely unknown type (99).
            t.addOpaqueTag(59999, 12, 2, ByteArray(16))
            t.addOpaqueTag(59998, 99, 1, ByteArray(4))
            // Oppo CPH2737 ships ForwardMatrix1 as a garbage count=1 zero SRATIONAL.
            t.addSrational(50964, floatArrayOf(0f))
        }
        t.addLong(273, 0L) // StripOffsets placeholder, patched once pixel layout is known
        val file = t.build(pixels)
        patchStripOffset(file, width * height * 2)
        return file
    }

    private fun cfaPattern(cfa: CfaLayout): ByteArray {
        val p = ByteArray(4)
        for (y in 0..1) for (x in 0..1) p[y * 2 + x] = cfa.channelAt(x, y).toByte()
        return p
    }

    /** Rewrites the StripOffsets entry (tag 273) with the offset of the pixel block at EOF. */
    private fun patchStripOffset(file: File, pixelBytes: Int) {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val ifdOffset = bb.getInt(4)
        val count = bb.getShort(ifdOffset).toInt() and 0xFFFF
        val pixelOffset = bytes.size - pixelBytes
        for (i in 0 until count) {
            val base = ifdOffset + 2 + i * 12
            if ((bb.getShort(base).toInt() and 0xFFFF) == 273) {
                bb.putInt(base + 8, pixelOffset)
                file.writeBytes(bb.array())
                return
            }
        }
        throw AssertionError("tag 273 not found for patching")
    }

    private class TiffBuilder(private val width: Int, private val height: Int) {
        private val order = ByteOrder.LITTLE_ENDIAN
        private val ifdEntries = ArrayList<ByteArray>()
        private val pendingOffsets = ArrayList<Pending>()

        private class Pending(val entry: ByteArray, val value: ByteArray)

        fun addShort(tag: Int, vararg values: Int) =
            add(tag, 3, values.size, pack { b -> values.forEach { b.putShort(it.toShort()) } }, values.size * 2)

        fun addLong(tag: Int, vararg values: Long) =
            add(tag, 4, values.size, pack { b -> values.forEach { b.putInt(it.toInt()) } }, values.size * 4)

        fun addRational(tag: Int, pairs: List<Pair<Int, Int>>) =
            add(tag, 5, pairs.size, pack { b -> pairs.forEach { b.putInt(it.first); b.putInt(it.second) } }, pairs.size * 8)

        fun addSrational(tag: Int, values: FloatArray) =
            add(tag, 10, values.size, pack { b -> values.forEach { b.putInt((it * 10000).toInt()); b.putInt(10000) } }, values.size * 8)

        fun addBytes(tag: Int, bytes: ByteArray) = add(tag, 1, bytes.size, bytes.copyOf(), bytes.size)

        fun addOpaqueTag(tag: Int, type: Int, count: Int, value: ByteArray) =
            add(tag, type, count, value.copyOf(), value.size)

        private fun pack(block: (ByteBuffer) -> Unit): ByteArray {
            val buf = ByteBuffer.allocate(1024).order(order)
            block(buf)
            return buf.array().copyOf(buf.position())
        }

        private fun add(tag: Int, type: Int, count: Int, value: ByteArray, valueSize: Int) {
            val entry = ByteArray(12)
            val b = ByteBuffer.wrap(entry).order(order)
            b.putShort(tag.toShort())
            b.putShort(type.toShort())
            b.putInt(count)
            if (valueSize <= 4) {
                for (i in 0 until valueSize) b.put(8 + i, value[i])
            } else {
                // Out-of-line values get their offset assigned in build().
                pendingOffsets += Pending(entry, value)
            }
            ifdEntries += entry
        }

        fun build(pixelData: ByteArray): File {
            val ifdOffset = 8
            val ifdSize = 2 + ifdEntries.size * 12 + 4
            var dataOffset = ifdOffset + ifdSize
            val resolved = pendingOffsets.map { p ->
                val off = dataOffset
                dataOffset += p.value.size
                off to p
            }
            resolved.forEach { (off, p) -> ByteBuffer.wrap(p.entry).order(order).putInt(8, off) }
            val out = ByteArrayOutputStream()
            val head = ByteBuffer.allocate(8).order(order)
            head.put('I'.code.toByte()); head.put('I'.code.toByte())
            head.putShort(42); head.putInt(ifdOffset)
            out.write(head.array())
            val ifd = ByteBuffer.allocate(ifdSize).order(order)
            ifd.putShort(ifdEntries.size.toShort())
            ifdEntries.forEach { ifd.put(it) }
            ifd.putInt(0)
            out.write(ifd.array())
            resolved.forEach { (_, p) -> out.write(p.value) }
            out.write(pixelData)
            val file = File.createTempFile("dng-test", ".dng")
            file.deleteOnExit()
            file.writeBytes(out.toByteArray())
            return file
        }
    }
}
