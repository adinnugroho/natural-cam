package com.adin.naturalcam.image

import com.adin.naturalcam.image.core.BayerImage
import com.adin.naturalcam.image.core.CfaLayout
import com.adin.naturalcam.image.core.LensShadingMap
import com.adin.naturalcam.image.raw.DngReader
import com.adin.naturalcam.image.raw.LensShadingCorrector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DNG OpcodeList2 GainMap parsing and application (SPEC 31). The layout mirrors
 * what the Oppo CPH2737 HAL writes: four single-plane maps (one per CFA
 * position), a 17x17 grid, and a big-endian payload inside a little-endian DNG.
 */
class LensShadingTest {

    private val opcodeGainMap = 9

    private fun entry(order: ByteOrder, id: Int, params: ByteArray): ByteArray {
        val out = ByteArray(16 + params.size)
        val bb = ByteBuffer.wrap(out).order(order)
        bb.putInt(0, id)
        bb.putInt(4, 0x01030000)
        bb.putInt(8, 1)
        bb.putInt(12, params.size)
        System.arraycopy(params, 0, out, 16, params.size)
        return out
    }

    private fun gainMapParams(
        order: ByteOrder,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
        rows: Int,
        columns: Int,
        values: FloatArray,
        pad: Int = 0,
    ): ByteArray {
        val out = ByteArray(40 + pad + values.size * 4)
        val bb = ByteBuffer.wrap(out).order(order)
        bb.putInt(0, top)
        bb.putInt(4, left)
        bb.putInt(8, bottom)
        bb.putInt(12, right)
        bb.putInt(16, 0) // plane
        bb.putInt(20, 1) // planes
        bb.putInt(24, 2) // row pitch
        bb.putInt(28, 2) // column pitch
        bb.putInt(32, rows)
        bb.putInt(36, columns)
        for (i in values.indices) bb.putFloat(40 + pad + i * 4, values[i])
        return out
    }

    private fun opcodeList(order: ByteOrder, vararg opcodes: ByteArray): ByteArray {
        val out = ByteArray(4 + opcodes.sumOf { it.size })
        ByteBuffer.wrap(out).order(order).putInt(0, opcodes.size)
        var pos = 4
        for (op in opcodes) {
            System.arraycopy(op, 0, out, pos, op.size)
            pos += op.size
        }
        return out
    }

    /** Four maps, one per CFA position, each a constant gain. */
    private fun constantMaps(order: ByteOrder, image: Int, gains: FloatArray, pad: Int = 0): ByteArray {
        val ops = ArrayList<ByteArray>()
        for (top in 0..1) for (left in 0..1) {
            val key = top * 2 + left
            ops += entry(
                order,
                opcodeGainMap,
                gainMapParams(
                    order, top, left, image, image, 2, 2,
                    FloatArray(4) { gains[key] }, pad,
                ),
            )
        }
        return opcodeList(order, *ops.toTypedArray())
    }

    @Test
    fun `gain map is read for both payload byte orders`() {
        val values = floatArrayOf(2f, 2f, 2f, 1f)
        for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            val payload = opcodeList(order, entry(order, opcodeGainMap, gainMapParams(order, 0, 0, 2, 2, 2, 2, values)))
            val map = DngReader.parseLensShading(payload, 2, 2)
            assertNotNull("order=$order", map)
            assertEquals(2, map!!.columns)
            assertEquals(2, map.rows)
            assertArrayEquals(values, map.grids[0]!!, 1e-6f)
            assertNull(map.grids[1])
        }
    }

    /** The grid sits at the end of the parameter block, so padding before it must not shift it. */
    @Test
    fun `gain map data is located by parameter size, not a fixed offset`() {
        val values = FloatArray(289) { it.toFloat() }
        val order = ByteOrder.BIG_ENDIAN
        val payload = opcodeList(order, entry(order, opcodeGainMap, gainMapParams(order, 1, 1, 8, 8, 17, 17, values, pad = 36)))
        val map = DngReader.parseLensShading(payload, 8, 8)
        assertNotNull(map)
        assertArrayEquals(values, map!!.grids[3]!!, 1e-6f)
    }

    @Test
    fun `other opcodes yield no map`() {
        val order = ByteOrder.LITTLE_ENDIAN
        val payload = opcodeList(order, entry(order, 6, ByteArray(8)))
        assertNull(DngReader.parseLensShading(payload, 2, 2))
    }

    @Test
    fun `map that does not cover the whole frame is rejected`() {
        val order = ByteOrder.LITTLE_ENDIAN
        val values = FloatArray(4) { 2f }
        val params = gainMapParams(order, 0, 0, 1, 2, 2, 2, values) // bottom=1 < height=2
        assertNull(DngReader.parseLensShading(opcodeList(order, entry(order, opcodeGainMap, params)), 2, 2))
    }

    @Test
    fun `corrector applies the gain for each CFA position`() {
        val bayer = BayerImage(2, 2, CfaLayout.BGGR, FloatArray(4) { 0.1f })
        val map = DngReader.parseLensShading(constantMaps(ByteOrder.BIG_ENDIAN, 2, floatArrayOf(1f, 2f, 3f, 4f)), 2, 2)
        assertNotNull(map)
        LensShadingCorrector.correct(bayer, map!!)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), bayer.values, 1e-6f)
    }

    @Test
    fun `a gain common to every CFA position is applied to all of them`() {
        val bayer = BayerImage(2, 2, CfaLayout.BGGR, FloatArray(4) { 0.5f })
        val map = DngReader.parseLensShading(constantMaps(ByteOrder.BIG_ENDIAN, 2, FloatArray(4) { 3f }), 2, 2)!!
        LensShadingCorrector.correct(bayer, map)
        assertArrayEquals(floatArrayOf(1.5f, 1.5f, 1.5f, 1.5f), bayer.values, 1e-6f)
    }

    /** The smoother scales its strength by this, so it must be the plain mean. */
    @Test
    fun `mean grid averages the CFA positions`() {
        val map = DngReader.parseLensShading(
            constantMaps(ByteOrder.BIG_ENDIAN, 2, floatArrayOf(1f, 2f, 3f, 4f)),
            2,
            2,
        )!!
        assertArrayEquals(FloatArray(4) { 2.5f }, map.meanGrid()!!, 1e-6f)
    }

    @Test
    fun `corrector leaves samples with no map untouched`() {
        val bayer = BayerImage(2, 2, CfaLayout.BGGR, FloatArray(4) { 0.5f })
        val order = ByteOrder.BIG_ENDIAN
        val payload = opcodeList(order, entry(order, opcodeGainMap, gainMapParams(order, 0, 0, 2, 2, 2, 2, FloatArray(4) { 2f })))
        val map = DngReader.parseLensShading(payload, 2, 2)!!
        LensShadingCorrector.correct(bayer, map)
        assertArrayEquals(floatArrayOf(1f, 0.5f, 0.5f, 0.5f), bayer.values, 1e-6f)
    }

    @Test
    fun `corrector refuses a map from a different frame`() {
        val bayer = BayerImage(2, 2, CfaLayout.BGGR, FloatArray(4) { 0.5f })
        val map = LensShadingMap(2, 2, 4, 4, arrayOf(FloatArray(4) { 2f }, null, null, null))
        LensShadingCorrector.correct(bayer, map)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f), bayer.values, 1e-6f)
    }
}
