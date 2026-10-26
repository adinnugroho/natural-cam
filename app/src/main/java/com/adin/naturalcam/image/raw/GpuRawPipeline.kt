package com.adin.naturalcam.image.raw

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log
import com.adin.naturalcam.image.core.CfaLayout
import com.adin.naturalcam.image.core.RawImage
import com.adin.naturalcam.image.core.RgbGains
import com.adin.naturalcam.image.core.SATURATION_CHROMA_DENOISE
import com.adin.naturalcam.image.core.STYLE_CHROMA_DENOISE_STRENGTH
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * GPU-native RAW pipeline using multi-pass rendering.
 *
 * Pass 1: normalize + demosaic + WB + color matrix → RGBA16F (expensive, runs once)
 * Pass 2: residual WB + NATURAL tone + highlight roll-off + clipped-neutralize → RGBA16F
 * Pass 3: denoise + sharpen + style pads/saturation → RGBA16F (linear)
 * Bloom:  peak mask at 1/16 + six 3x3 blur passes → RGBA16F (skipped at bloom 0)
 * Composite: glow + sRGB encode → RGBA8
 *
 * Only the final 8-bit frame crosses the GPU/CPU boundary. Grain stays on the CPU
 * because it belongs to the delivered pixels, and it is colour-free there.
 *
 * Supported contract:
 * - RAW sensor input (16-bit CFA mosaic)
 * - NATURAL and PURE profiles, any style state
 * - AsShotNeutral + camera matrix required
 */
internal object GpuRawPipeline {
    private const val TAG = "GpuRawPipeline"
    private const val READBACK_ROWS = 64
    private const val REDUCTION_SIZE = 32

    /** Must match BloomStage: mask cell = 16 full-res px, six 3x3 blur passes. */
    private const val BLOOM_DOWNSAMPLE = 16
    private const val BLOOM_BLUR_PASSES = 6

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gpu-raw-pipeline")
    }
    private var session: Session? = null
    private var sessionKey: SessionKey? = null

    data class Result(val argb: IntArray, val width: Int, val height: Int)

    fun tryRender(raw: RawImage, config: GpuPipelineConfig): Result? {
        return try {
            val start = SystemClock.elapsedRealtime()
            val result = executor.submit<Result> {
                val key = SessionKey.from(raw)
                if (sessionKey != key) {
                    session?.close()
                    session = Session(raw)
                    sessionKey = key
                }
                session!!.render(raw, config)
            }.get()
            Log.i(
                TAG,
                "GPU render ${result.width}x${result.height} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - start}",
            )
            result
        } catch (error: Exception) {
            Log.w(TAG, "GPU pipeline unavailable; using CPU reference", error)
            reset()
            null
        } catch (error: LinkageError) {
            Log.w(TAG, "GPU pipeline linkage unavailable; using CPU reference", error)
            reset()
            null
        }
    }

    private fun reset() {
        runCatching {
            executor.submit<Unit> {
                session?.close()
                session = null
                sessionKey = null
            }.get()
        }
    }

    private data class SessionKey(
        val width: Int,
        val height: Int,
        val cfa: CfaLayout,
        val whiteLevel: Int,
    ) {
        companion object {
            fun from(raw: RawImage): SessionKey = SessionKey(
                raw.width, raw.height, raw.metadata.cfa, raw.metadata.whiteLevel,
            )
        }
    }

    private class Session(private val reference: RawImage) : AutoCloseable {
        private val width = reference.width
        private val height = reference.height

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var framebuffer = 0

        private var rawTexture = 0
        private var textureA = 0   // Pass 1 output; pass 3 writes the styled linear result here
        private var textureB = 0   // Pass 2 output: tone-mapped
        private var outputTexture = 0  // Composite output: sRGB RGBA8
        private var reductionTexture = 0  // 32x32 residual WB cell sums (RGBA16F)
        private var gainsTexture = 0      // 1x1 residual WB gains
        private var maskTextureA = 0   // Bloom mask, 1/BLOOM_DOWNSAMPLE resolution
        private var maskTextureB = 0
        private var bloomMaskTexture = 0  // whichever mask holds the final blurred glow

        private val maskWidth = maxOf(1, width / BLOOM_DOWNSAMPLE)
        private val maskHeight = maxOf(1, height / BLOOM_DOWNSAMPLE)

        private var pass1Program = 0
        private var pass2Program = 0
        private var pass3Program = 0
        private var reductionProgram = 0
        private var gainsProgram = 0
        private var maskProgram = 0
        private var blurProgram = 0
        private var compositeProgram = 0

        init {
            initializeEgl()
            initializeResources()
        }

        fun render(raw: RawImage, config: GpuPipelineConfig): Result {
            uploadRaw(raw)
            runPass1(raw, config)
            // Residual auto-WB: gray-world correction on Pass 1 output, same as CPU pipeline.
            runResidualWb()
            if (config.natural) {
                runPass2(config)
                runPass3(config, textureB)
            } else {
                runPass2Pure()
                runPass3Pure(textureB)
            }
            // Style bloom is spatial and needs the styled linear frame, so it runs after
            // pass 3 and before the sRGB encode.
            var bloomAmount = 0f
            if (config.natural && config.bloom > 0f) {
                runBloom()
                bloomAmount = config.bloom.coerceIn(0f, 1f)
            }
            runComposite(bloomAmount)
            return readOutput()
        }

        override fun close() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            if (context != EGL14.EGL_NO_CONTEXT) {
                GLES30.glDeleteProgram(pass1Program)
                GLES30.glDeleteProgram(pass2Program)
                GLES30.glDeleteProgram(pass3Program)
                GLES30.glDeleteProgram(reductionProgram)
                GLES30.glDeleteProgram(gainsProgram)
                GLES30.glDeleteProgram(maskProgram)
                GLES30.glDeleteProgram(blurProgram)
                GLES30.glDeleteProgram(compositeProgram)
                GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
                GLES30.glDeleteTextures(
                    8,
                    intArrayOf(
                        rawTexture, textureA, textureB, outputTexture, reductionTexture,
                        gainsTexture, maskTextureA, maskTextureB,
                    ),
                    0,
                )
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            surface = EGL14.EGL_NO_SURFACE
        }

        private fun initializeEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "EGL display unavailable" }
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "EGL init failed" }

            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] == 1) {
                "EGL ES3 config unavailable"
            }
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "EGL ES3 context unavailable" }
            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            check(surface != EGL14.EGL_NO_SURFACE) { "EGL pbuffer unavailable" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL make-current failed" }
        }

        private fun initializeResources() {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            framebuffer = ids[0]

            rawTexture = createTexture(GLES30.GL_R16UI, width, height)
            textureA = createTexture(GLES30.GL_RGBA16F, width, height)
            textureB = createTexture(GLES30.GL_RGBA16F, width, height)
            outputTexture = createTexture(GLES30.GL_RGBA8, width, height)
            reductionTexture = createTexture(GLES30.GL_RGBA16F, REDUCTION_SIZE, REDUCTION_SIZE)
            gainsTexture = createTexture(GLES30.GL_RGBA16F, 1, 1)
            maskTextureA = createTexture(GLES30.GL_RGBA16F, maskWidth, maskHeight)
            maskTextureB = createTexture(GLES30.GL_RGBA16F, maskWidth, maskHeight)
            bloomMaskTexture = maskTextureA

            pass1Program = link(PASS1_SHADER)
            pass2Program = link(PASS2_SHADER)
            pass3Program = link(PASS3_SHADER)
            reductionProgram = link(REDUCTION_SHADER)
            gainsProgram = link(GAINS_SHADER)
            maskProgram = link(BLOOM_MASK_SHADER)
            blurProgram = link(BLOOM_BLUR_SHADER)
            compositeProgram = link(COMPOSITE_SHADER)
        }

        private fun createTexture(internalFormat: Int, w: Int, h: Int): Int {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internalFormat, w, h)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            return ids[0]
        }

        private fun uploadRaw(raw: RawImage) {
            val bytes = ByteBuffer.allocateDirect(raw.pixelData.size * 2).order(ByteOrder.nativeOrder())
            bytes.asShortBuffer().put(raw.pixelData)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTexture)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, bytes,
            )
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }

        // ---- Pass 1: normalize + demosaic + WB + color matrix ----
        private fun runPass1(raw: RawImage, config: GpuPipelineConfig) {
            attach(textureA)
            GLES30.glUseProgram(pass1Program)
            bindTexture(pass1Program, "uRaw", 0, rawTexture)
            uniform4f(pass1Program, "uBlack", blackVector(raw))
            uniform1f(pass1Program, "uWhite", raw.metadata.whiteLevel.toFloat())
            uniform1i(pass1Program, "uCfa", cfaCode(raw.metadata.cfa))
            uniform2i(pass1Program, "uImageSize", width, height)
            uniform3f(pass1Program, "uGains", floatArrayOf(config.wbGains.r, config.wbGains.g, config.wbGains.b))
            // GLSL mat3 is column-major; CPU matrix is row-major. Transpose before upload.
            uniformMatrix3(pass1Program, "uMatrix", transposeMatrix3(config.colorMatrix))
            drawFullscreen()
        }

        // ---- Residual auto-WB: reduce to per-cell sums, then to one gains texel ----
        private fun runResidualWb() {
            attach(reductionTexture, REDUCTION_SIZE, REDUCTION_SIZE)
            GLES30.glUseProgram(reductionProgram)
            bindTexture(reductionProgram, "uInput", 0, textureA)
            uniform2i(reductionProgram, "uInputSize", width, height)
            uniform2i(reductionProgram, "uReductionSize", REDUCTION_SIZE, REDUCTION_SIZE)
            drawFullscreen()

            // Collapsing on the GPU avoids a readback stall and any dependency on
            // float render targets: pass 2 samples the single texel directly.
            attach(gainsTexture, 1, 1)
            GLES30.glUseProgram(gainsProgram)
            bindTexture(gainsProgram, "uReduction", 0, reductionTexture)
            uniform2i(gainsProgram, "uReductionSize", REDUCTION_SIZE, REDUCTION_SIZE)
            drawFullscreen()
        }

        private fun transposeMatrix3(m: FloatArray): FloatArray = floatArrayOf(
            m[0], m[3], m[6],
            m[1], m[4], m[7],
            m[2], m[5], m[8],
        )

        // ---- Pass 2: residual WB + NATURAL tone + highlight roll-off + clipped-neutralize ----
        private fun runPass2(config: GpuPipelineConfig) {
            attach(textureB)
            GLES30.glUseProgram(pass2Program)
            bindTexture(pass2Program, "uInput", 0, textureA)
            bindTexture(pass2Program, "uGains", 1, gainsTexture)
            uniform1f(pass2Program, "uWarmth", config.warmth)
            uniform1f(pass2Program, "uExposureGain", config.exposureGain)
            uniform1f(pass2Program, "uToneGamma", config.midtoneGamma)
            uniform1f(pass2Program, "uToneContrast", config.contrast)
            uniform1f(pass2Program, "uRollStart", config.highlightRollOffStart)
            uniform1f(pass2Program, "uRollCompression", config.highlightCompression)
            drawFullscreen()
        }

        // ---- Pass 2 PURE: residual WB + clipped-neutralize ----
        private fun runPass2Pure() {
            attach(textureB)
            GLES30.glUseProgram(pass2Program)
            bindTexture(pass2Program, "uInput", 0, textureA)
            bindTexture(pass2Program, "uGains", 1, gainsTexture)
            uniform1f(pass2Program, "uWarmth", 0f)
            uniform1f(pass2Program, "uExposureGain", 1f)
            uniform1f(pass2Program, "uToneGamma", 1f)
            uniform1f(pass2Program, "uToneContrast", 0f)
            uniform1f(pass2Program, "uRollStart", 1f)
            uniform1f(pass2Program, "uRollCompression", 0f)
            drawFullscreen()
        }

        // ---- Pass 3: denoise + sharpen + style, written linear for the composite ----
        private fun runPass3(config: GpuPipelineConfig, inputTexture: Int) {
            attach(textureA)
            GLES30.glUseProgram(pass3Program)
            bindTexture(pass3Program, "uInput", 0, inputTexture)
            uniform2i(pass3Program, "uImageSize", width, height)
            // The style chain denoises once for its own strength and once more when the
            // user boosts saturation; CPU runs them as separate smoothers, folding both
            // into this pass's strength is the closest single-pass equivalent.
            val styleChroma = STYLE_CHROMA_DENOISE_STRENGTH * config.styleStrength +
                if (config.saturation > 0f) SATURATION_CHROMA_DENOISE * config.saturation else 0f
            uniform1f(pass3Program, "uChromaStrength", config.chromaStrength + styleChroma)
            uniform1f(pass3Program, "uLumaStrength", config.lumaStrength)
            uniform1f(pass3Program, "uSharpenAmount", config.sharpenAmount)
            uniform1i(pass3Program, "uNatural", 1)
            uniform4f(
                pass3Program,
                "uStyleA",
                floatArrayOf(
                    config.toneContrast, config.midtoneLift,
                    config.shoulderStrength, config.temperature,
                ),
            )
            uniform4f(
                pass3Program,
                "uStyleB",
                floatArrayOf(
                    config.chromaScale, config.chromaCompression,
                    config.paletteHueShift, config.paletteChromaBoost,
                ),
            )
            uniform4f(
                pass3Program,
                "uStyleC",
                floatArrayOf(config.shadowHueTint, config.saturation, 0f, 0f),
            )
            drawFullscreen()
        }

        // ---- Pass 3 PURE: no denoise, sharpen or style; linear for the composite ----
        private fun runPass3Pure(inputTexture: Int) {
            attach(textureA)
            GLES30.glUseProgram(pass3Program)
            bindTexture(pass3Program, "uInput", 0, inputTexture)
            uniform2i(pass3Program, "uImageSize", width, height)
            uniform1f(pass3Program, "uChromaStrength", 0f)
            uniform1f(pass3Program, "uLumaStrength", 0f)
            uniform1f(pass3Program, "uSharpenAmount", 0f)
            uniform1i(pass3Program, "uNatural", 0)
            uniform4f(pass3Program, "uStyleA", floatArrayOf(0f, 0f, 0f, 0f))
            uniform4f(pass3Program, "uStyleB", floatArrayOf(1f, 0f, 0f, 0f))
            uniform4f(pass3Program, "uStyleC", floatArrayOf(0f, 0f, 0f, 0f))
            drawFullscreen()
        }

        // ---- Bloom: peak mask at 1/16, six 3x3 blur passes, then upsample in composite ----
        private fun runBloom() {
            attach(maskTextureA, maskWidth, maskHeight)
            GLES30.glUseProgram(maskProgram)
            bindTexture(maskProgram, "uInput", 0, textureA)
            uniform2i(maskProgram, "uImageSize", width, height)
            uniform2i(maskProgram, "uMaskSize", maskWidth, maskHeight)
            drawFullscreen()

            var source = maskTextureA
            var target = maskTextureB
            repeat(BLOOM_BLUR_PASSES) {
                attach(target, maskWidth, maskHeight)
                GLES30.glUseProgram(blurProgram)
                bindTexture(blurProgram, "uInput", 0, source)
                uniform2i(blurProgram, "uMaskSize", maskWidth, maskHeight)
                drawFullscreen()
                val previous = source
                source = target
                target = previous
            }
            // An even pass count lands back on maskTextureA; keep whichever one holds it.
            bloomMaskTexture = source
        }

        // ---- Composite: add glow, encode sRGB, write the deliverable ----
        private fun runComposite(bloomAmount: Float) {
            attach(outputTexture)
            GLES30.glUseProgram(compositeProgram)
            bindTexture(compositeProgram, "uInput", 0, textureA)
            bindTexture(compositeProgram, "uMask", 1, bloomMaskTexture)
            uniform2i(compositeProgram, "uImageSize", width, height)
            uniform2i(compositeProgram, "uMaskSize", maskWidth, maskHeight)
            uniform1f(compositeProgram, "uBloomAmount", bloomAmount)
            drawFullscreen()
        }

        private fun readOutput(): Result {
            attach(outputTexture)
            val argb = IntArray(width * height)
            val rows = minOf(READBACK_ROWS, height)
            val bytes = ByteBuffer.allocateDirect(width * rows * 4).order(ByteOrder.nativeOrder())
            var startY = 0
            while (startY < height) {
                val rowCount = minOf(rows, height - startY)
                bytes.clear()
                GLES30.glReadPixels(0, startY, width, rowCount, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
                for (row in 0 until rowCount) {
                    val source = row * width * 4
                    val target = (startY + row) * width
                    for (x in 0 until width) {
                        val offset = source + x * 4
                        val r = bytes.get(offset).toInt() and 0xFF
                        val g = bytes.get(offset + 1).toInt() and 0xFF
                        val b = bytes.get(offset + 2).toInt() and 0xFF
                        argb[target + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                startY += rowCount
            }
            return Result(argb, width, height)
        }

        private fun attach(texture: Int, w: Int = width, h: Int = height) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0,
            )
            GLES30.glViewport(0, 0, w, h)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "Framebuffer incomplete for texture=$texture"
            }
        }

        private fun drawFullscreen() {
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glUseProgram(0)
        }

        private fun bindTexture(program: Int, name: String, unit: Int, texture: Int) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit)
        }

        private fun uniform1f(program: Int, name: String, value: Float) =
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, name), value)

        private fun uniform1i(program: Int, name: String, value: Int) =
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), value)

        private fun uniform2i(program: Int, name: String, x: Int, y: Int) =
            GLES30.glUniform2i(GLES30.glGetUniformLocation(program, name), x, y)

        private fun uniform3f(program: Int, name: String, values: FloatArray) =
            GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, name), 1, values, 0)

        private fun uniform4f(program: Int, name: String, values: FloatArray) =
            GLES30.glUniform4fv(GLES30.glGetUniformLocation(program, name), 1, values, 0)

        private fun uniformMatrix3(program: Int, name: String, values: FloatArray) =
            GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(program, name), 1, false, values, 0)

        private fun link(fragmentSource: String): Int {
            val vertex = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            val linked = GLES30.glCreateProgram()
            GLES30.glAttachShader(linked, vertex)
            GLES30.glAttachShader(linked, fragment)
            GLES30.glLinkProgram(linked)
            val status = IntArray(1)
            GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0)
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
            check(status[0] != 0) { GLES30.glGetProgramInfoLog(linked) }
            return linked
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source.trimIndent())
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES30.glGetShaderInfoLog(shader) }
            return shader
        }
    }

    private fun blackVector(raw: RawImage): FloatArray {
        val source = raw.metadata.blackLevelPerChannel
        return FloatArray(4) { index -> source[index.coerceAtMost(source.lastIndex)] }
    }

    private fun cfaCode(cfa: CfaLayout): Int = when (cfa) {
        CfaLayout.RGGB -> 0
        CfaLayout.GRBG -> 1
        CfaLayout.GBRG -> 2
        CfaLayout.BGGR -> 3
    }

    private const val VERTEX_SHADER = """
        #version 300 es
        precision highp float;
        const vec2 POSITIONS[3] = vec2[3](
            vec2(-1.0, -1.0),
            vec2(3.0, -1.0),
            vec2(-1.0, 3.0)
        );
        void main() { gl_Position = vec4(POSITIONS[gl_VertexID], 0.0, 1.0); }
    """

    // ---- Pass 1: RAW → demosaic → WB → color matrix (RGBA16F output) ----
    private const val PASS1_SHADER = """
        #version 300 es
        precision highp float;
        precision highp int;
        precision highp usampler2D;

        uniform usampler2D uRaw;
        uniform vec4 uBlack;
        uniform float uWhite;
        uniform int uCfa;
        uniform ivec2 uImageSize;
        uniform vec3 uGains;
        uniform mat3 uMatrix;
        layout(location = 0) out vec4 outColor;

        const float MISSING = -1.0;

        int parity(int v) { return v - (v / 2) * 2; }

        int cfaChannel(ivec2 p) {
            int row = parity(p.y);
            int col = parity(p.x);
            if (uCfa == 0) return row == 0 ? (col == 0 ? 0 : 1) : (col == 0 ? 1 : 2);
            if (uCfa == 1) return row == 0 ? (col == 0 ? 1 : 0) : (col == 0 ? 2 : 1);
            if (uCfa == 2) return row == 0 ? (col == 0 ? 1 : 2) : (col == 0 ? 0 : 1);
            return row == 0 ? (col == 0 ? 2 : 1) : (col == 0 ? 1 : 0);
        }

        float blackAt(int c) { return c == 0 ? uBlack.r : (c == 1 ? uBlack.g : uBlack.b); }

        bool inside(ivec2 p) { return p.x >= 0 && p.y >= 0 && p.x < uImageSize.x && p.y < uImageSize.y; }

        float rawSample(ivec2 p) {
            ivec2 clamped = clamp(p, ivec2(0), uImageSize - ivec2(1));
            int channel = cfaChannel(clamped);
            float range = max(uWhite - blackAt(channel), 1.0);
            return (float(texelFetch(uRaw, clamped, 0).r) - blackAt(channel)) / range;
        }

        float probe(ivec2 p, int channel, ivec2 dir) {
            for (int d = 1; d <= 2; d++) {
                ivec2 q = p + dir * d;
                if (!inside(q)) return MISSING;
                if (cfaChannel(q) == channel) return rawSample(q);
            }
            return MISSING;
        }

        float hNearest(ivec2 p, int channel) {
            float l = probe(p, channel, ivec2(-1, 0));
            float r = probe(p, channel, ivec2(1, 0));
            if (l > -0.5 && r > -0.5) return (l + r) * 0.5;
            if (l > -0.5) return l;
            return r;
        }

        float vNearest(ivec2 p, int channel) {
            float u = probe(p, channel, ivec2(0, -1));
            float d = probe(p, channel, ivec2(0, 1));
            if (u > -0.5 && d > -0.5) return (u + d) * 0.5;
            if (u > -0.5) return u;
            return d;
        }

        vec2 diagSum(ivec2 p, int channel) {
            float total = 0.0; float count = 0.0; ivec2 q;
            q = p + ivec2(-1, -1); if (inside(q) && cfaChannel(q) == channel) { total += rawSample(q); count += 1.0; }
            q = p + ivec2(1, -1);  if (inside(q) && cfaChannel(q) == channel) { total += rawSample(q); count += 1.0; }
            q = p + ivec2(-1, 1);  if (inside(q) && cfaChannel(q) == channel) { total += rawSample(q); count += 1.0; }
            q = p + ivec2(1, 1);   if (inside(q) && cfaChannel(q) == channel) { total += rawSample(q); count += 1.0; }
            return vec2(total, count);
        }

        float fallback(ivec2 p, int channel) {
            float h = hNearest(p, channel); if (h > -0.5) return h;
            float v = vNearest(p, channel); if (v > -0.5) return v;
            return rawSample(p);
        }

        float crossValue(ivec2 p, int channel) {
            float total = 0.0; float count = 0.0; ivec2 q;
            q = p + ivec2(-1, 0); if (inside(q) && cfaChannel(q) == channel) { total += rawSample(q); count += 1.0; }
            q = p + ivec2(1, 0);  if (inside(q) && cfaChannel(q) == channel) { total += rawSample(q); count += 1.0; }
            q = p + ivec2(0, -1); if (inside(q) && cfaChannel(q) == channel) { total += rawSample(q); count += 1.0; }
            q = p + ivec2(0, 1);  if (inside(q) && cfaChannel(q) == channel) { total += rawSample(q); count += 1.0; }
            return count > 0.0 ? total / count : fallback(p, channel);
        }

        float diagonal(ivec2 p, int channel) {
            vec2 s = diagSum(p, channel);
            return s.y > 0.0 ? s.x / s.y : fallback(p, channel);
        }

        float chromaAtGreen(ivec2 p, int channel) {
            float total = 0.0; float count = 0.0;
            float h = hNearest(p, channel); if (h > -0.5) { total += h; count += 1.0; }
            float v = vNearest(p, channel); if (v > -0.5) { total += v; count += 1.0; }
            vec2 d = diagSum(p, channel); if (d.y > 0.0) { total += d.x / d.y; count += 1.0; }
            return count > 0.0 ? total / count : rawSample(p);
        }

        vec3 demosaic(ivec2 p) {
            int own = cfaChannel(p);
            if (own == 0) return vec3(rawSample(p), crossValue(p, 1), diagonal(p, 2));
            if (own == 2) return vec3(diagonal(p, 0), crossValue(p, 1), rawSample(p));
            return vec3(chromaAtGreen(p, 0), rawSample(p), chromaAtGreen(p, 2));
        }

        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            vec3 value = demosaic(p);
            value *= uGains;
            value = uMatrix * value;
            outColor = vec4(value, 1.0);
        }
    """

    // ---- Residual auto-WB reduction: sums bright low-chroma samples per cell ----
    private const val REDUCTION_SHADER = """
        #version 300 es
        precision highp float;
        uniform sampler2D uInput;
        uniform ivec2 uInputSize;
        uniform ivec2 uReductionSize;
        layout(location = 0) out vec4 outColor;

        void main() {
            ivec2 cell = ivec2(gl_FragCoord.xy);
            ivec2 blockStart = cell * uInputSize / uReductionSize;
            ivec2 blockEnd = (cell + 1) * uInputSize / uReductionSize;
            vec3 sum = vec3(0.0);
            float count = 0.0;
            for (int y = blockStart.y; y < blockEnd.y; y++) {
                for (int x = blockStart.x; x < blockEnd.x; x++) {
                    vec3 v = texelFetch(uInput, ivec2(x, y), 0).rgb;
                    // Same selection as CPU WhiteBalanceStage.auto:
                    // bright, low-chroma samples only
                    float lum = (v.r + v.g + v.b) / 3.0;
                    float chroma = max(v.r, max(v.g, v.b)) - min(v.r, min(v.g, v.b));
                    if (lum >= 0.35 && lum <= 0.98 && chroma <= max(0.03, lum * 0.25)) {
                        sum += v;
                        count += 1.0;
                    }
                }
            }
            outColor = vec4(sum, count);
        }
    """

    // ---- Residual auto-WB collapse: cell sums → one gains texel ----
    private const val GAINS_SHADER = """
        #version 300 es
        precision highp float;
        uniform sampler2D uReduction;
        uniform ivec2 uReductionSize;
        layout(location = 0) out vec4 outColor;

        void main() {
            vec3 sum = vec3(0.0);
            float count = 0.0;
            for (int y = 0; y < uReductionSize.y; y++) {
                for (int x = 0; x < uReductionSize.x; x++) {
                    vec4 cell = texelFetch(uReduction, ivec2(x, y), 0);
                    sum += cell.rgb;
                    count += cell.a;
                }
            }
            if (count < 32.0) {
                outColor = vec4(1.0, 1.0, 1.0, 1.0);
                return;
            }
            vec3 mean = sum / count;
            // gain = meanGreen / channel, clamped like WhiteBalanceStage.auto.
            outColor = vec4(
                clamp(mean.g / max(mean.r, 1e-6), 0.5, 2.0),
                1.0,
                clamp(mean.g / max(mean.b, 1e-6), 0.5, 2.0),
                1.0
            );
        }
    """

    // ---- Pass 2: residual WB + NATURAL tone + highlight roll-off + clipped-neutralize ----
    private const val PASS2_SHADER = """
        #version 300 es
        precision highp float;
        uniform sampler2D uInput;
        uniform sampler2D uGains;
        uniform float uWarmth;
        uniform float uExposureGain;
        uniform float uToneGamma;
        uniform float uToneContrast;
        uniform float uRollStart;
        uniform float uRollCompression;
        layout(location = 0) out vec4 outColor;

        float luma(vec3 c) { return c.r * 0.2126 + c.g * 0.7152 + c.b * 0.0722; }

        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            vec3 value = texelFetch(uInput, p, 0).rgb;

            // Residual gray-world WB (matches CPU WhiteBalanceStage.auto), collapsed to
            // a single texel by the gains pass.
            value *= texelFetch(uGains, ivec2(0), 0).rgb;
            // Warmth
            value *= vec3(1.0 + uWarmth, 1.0, 1.0 - uWarmth);
            // Exposure
            value *= uExposureGain;

            // Tone map
            float l = luma(value);
            if (l > 0.0) {
                float mapped = l <= 0.18
                    ? 0.18 * pow(max(l / 0.18, 0.0), uToneGamma)
                    : 0.18 * pow(max(l / 0.18, 0.0), 1.0 + uToneContrast);
                value *= mapped / l;
            }

            // Highlight roll-off
            float mx = max(value.r, max(value.g, value.b));
            if (mx > uRollStart && uRollStart < 1.0) {
                float span = 1.0 - uRollStart;
                float t = clamp((mx - uRollStart) / span, 0.0, 1.0);
                float exp = 1.0 + uRollCompression * 0.75;
                float mapped = uRollStart + span * pow(t, exp);
                value *= mapped / mx;
            }

            // Clipped highlight neutralization
            float mn = min(value.r, min(value.g, value.b));
            float md = value.r + value.g + value.b - mn - max(value.r, max(value.g, value.b));
            if (mn > 0.6 && md > 0.95) {
                float amt = clamp((md - 0.95) / 0.25, 0.0, 1.0);
                float neutral = (value.r + value.g + value.b) / 3.0;
                value = mix(value, vec3(neutral), amt);
            }

            outColor = vec4(value, 1.0);
        }
    """

    // ---- Pass 3: denoise + sharpen + style (linear RGBA16F output) ----
    private const val PASS3_SHADER = """
        #version 300 es
        precision highp float;
        uniform sampler2D uInput;
        uniform ivec2 uImageSize;
        uniform float uChromaStrength;
        uniform float uLumaStrength;
        uniform float uSharpenAmount;
        uniform int uNatural;
        uniform vec4 uStyleA;   // toneContrast, midtoneLift, shoulderStrength, temperature
        uniform vec4 uStyleB;   // chromaScale, chromaCompression, paletteHueShift, paletteChromaBoost
        uniform vec4 uStyleC;   // shadowHueTint, saturation, unused, unused
        layout(location = 0) out vec4 outColor;

        float luma(vec3 c) { return c.r * 0.2126 + c.g * 0.7152 + c.b * 0.0722; }

        vec3 fetch(ivec2 p) {
            ivec2 clamped = clamp(p, ivec2(0), uImageSize - ivec2(1));
            return texelFetch(uInput, clamped, 0).rgb;
        }

        // Mirrors StyleEngine.applyPalette: rose/green via chroma cross-feed (R/B only,
        // green untouched), gold/blue via a luma-weighted shadow tint, then a chroma boost
        // applied as a factor above 1.
        vec3 applyPalette(vec3 c) {
            float hue = uStyleB.z;
            float boost = uStyleB.w;
            float tint = uStyleC.x;
            if (hue == 0.0 && boost == 0.0 && tint == 0.0) return c;
            float l = luma(c);
            float cr = c.r - l;
            float cb = c.b - l;
            float newR = c.r + hue * 0.20 * cb;
            float newB = c.b - hue * 0.20 * cr;
            float chromaWeight = clamp((cr * cr + cb * cb) / (cr * cr + cb * cb + 0.02), 0.0, 1.0);
            float shadowWeight = exp(-l * 2.0) * 0.35 * chromaWeight;
            newR += tint * shadowWeight;
            newB -= tint * shadowWeight;
            if (boost > 0.0) {
                float factor = 1.0 + boost;
                newR = l + (newR - l) * factor;
                newB = l + (newB - l) * factor;
            }
            return vec3(max(newR, 0.0), c.g, max(newB, 0.0));
        }

        // Mirrors StyleEngine.applyColor: chroma scale with a soft knee, then a
        // chroma-weighted warm/cool shift that leaves grey exactly neutral.
        vec3 applyColor(vec3 c) {
            float temperature = uStyleA.w;
            float scale = uStyleB.x;
            float compression = uStyleB.y;
            if (temperature == 0.0 && scale == 1.0) return c;
            float l = luma(c);
            float cr = (c.r - l) * scale;
            float cg = (c.g - l) * scale;
            float cb = (c.b - l) * scale;
            if (compression > 0.0) {
                float mag = max(abs(cr), max(abs(cg), abs(cb)));
                if (mag > 0.35) {
                    float excess = (mag - 0.35) / 0.55;
                    float damp = 1.0 - compression * excess / (1.0 + excess * 0.7);
                    cr *= damp;
                    cg *= damp;
                    cb *= damp;
                }
            }
            float chromaMag = cr * cr + cb * cb;
            float chromaWeight = chromaMag / (chromaMag + 0.02);
            float warmShift = temperature * chromaWeight;
            return vec3(l + cr * (1.0 + warmShift), l + cg, l + cb * (1.0 - warmShift));
        }

        // Mirrors StyleEngine.applyTone, including the direction of each pad axis:
        // +x firms contrast around the 0.18 pivot, +y lifts shadows only.
        float toneChannel(float x, float contrastK) {
            float lift = uStyleA.y;
            float shoulder = uStyleA.z;
            if (lift > 0.0 && x < 0.35) {
                x *= 1.0 + lift * (1.0 - x / 0.35);
            } else if (lift < 0.0 && x > 0.30) {
                x += lift * (1.0 - (1.0 - x) / 0.70);
            }
            x = 0.18 + (x - 0.18) * contrastK;
            if (shoulder > 0.0 && x > 0.7) x -= shoulder * (x - 0.7) * 0.2;
            return max(x, 0.0);
        }

        vec3 applyTone(vec3 c) {
            if (uStyleA.x == 0.0 && uStyleA.y == 0.0 && uStyleA.z == 0.0) return c;
            float contrastK = 1.0 + uStyleA.x;
            return vec3(
                toneChannel(c.r, contrastK),
                toneChannel(c.g, contrastK),
                toneChannel(c.b, contrastK)
            );
        }

        // Mirrors StyleEngine.applySaturation: signed chroma scale about the pixel's luma.
        vec3 applySaturation(vec3 c) {
            float signed = uStyleC.y;
            if (signed == 0.0) return c;
            float scale = 1.0 + 0.6 * signed;
            float l = luma(c);
            return max(vec3(l) + (c - l) * scale, 0.0);
        }

        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            vec3 value = fetch(p);

            if (uNatural == 1) {
                // Denoise: 2x2 chroma + luma (samples RGBA16F result, NOT raw)
                if (uChromaStrength > 0.0 || uLumaStrength > 0.0) {
                    vec3 mean = (fetch(p) + fetch(p + ivec2(1, 0)) + fetch(p + ivec2(0, 1)) + fetch(p + ivec2(1, 1))) * 0.25;
                    float meanL = luma(mean);
                    float srcL = luma(value);
                    vec3 srcC = value - vec3(srcL);
                    vec3 meanC = mean - vec3(meanL);
                    float outL = srcL + uLumaStrength * (meanL - srcL);
                    value = vec3(outL) + srcC + uChromaStrength * (meanC - srcC);
                }

                // Sharpen: 3x3 binomial (samples RGBA16F result, NOT raw)
                if (uSharpenAmount > 0.0) {
                    float centerL = luma(value);
                    float blurL = 0.0;
                    float blurW = 0.0;
                    for (int y = -1; y <= 1; y++) {
                        for (int x = -1; x <= 1; x++) {
                            float w = float((x == 0 ? 2 : 1) * (y == 0 ? 2 : 1));
                            blurL += luma(fetch(p + ivec2(x, y))) * w;
                            blurW += w;
                        }
                    }
                    blurL /= blurW;
                    value += vec3((centerL - blurL) * uSharpenAmount);
                }
            }

            // Clipped highlight neutralization
            float mn = min(value.r, min(value.g, value.b));
            float md = value.r + value.g + value.b - mn - max(value.r, max(value.g, value.b));
            if (mn > 0.6 && md > 0.95) {
                float amt = clamp((md - 0.95) / 0.25, 0.0, 1.0);
                float neutral = (value.r + value.g + value.b) / 3.0;
                value = mix(value, vec3(neutral), amt);
            }

            // Style pads and saturation. Bloom is a separate spatial pass on the
            // linear result, grain is applied to the delivered pixels after readback.
            value = applyPalette(value);
            value = applyColor(value);
            value = applyTone(value);
            value = applySaturation(value);

            outColor = vec4(value, 1.0);
        }
    """

    // ---- Bloom mask: per-cell peak of the thresholded highlight, at 1/DOWNSAMPLE ----
    private const val BLOOM_MASK_SHADER = """
        #version 300 es
        precision highp float;
        uniform sampler2D uInput;
        uniform ivec2 uImageSize;
        uniform ivec2 uMaskSize;
        layout(location = 0) out vec4 outColor;

        const float THRESHOLD = 0.6;
        const float KNEE = 0.4;

        void main() {
            ivec2 cell = ivec2(gl_FragCoord.xy);
            ivec2 cellStart = cell * uImageSize / uMaskSize;
            ivec2 cellEnd = (cell + 1) * uImageSize / uMaskSize;
            vec3 peak = vec3(0.0);
            for (int y = cellStart.y; y < max(cellEnd.y, cellStart.y + 1); y++) {
                for (int x = cellStart.x; x < max(cellEnd.x, cellStart.x + 1); x++) {
                    vec3 value = max(texelFetch(uInput, ivec2(x, y), 0).rgb, 0.0);
                    vec3 knee = clamp((value - vec3(THRESHOLD)) / KNEE, 0.0, 1.0);
                    peak = max(peak, value * knee);
                }
            }
            outColor = vec4(peak, 1.0);
        }
    """

    // ---- Bloom blur: one 3x3 binomial pass at mask resolution (CPU does six) ----
    private const val BLOOM_BLUR_SHADER = """
        #version 300 es
        precision highp float;
        uniform sampler2D uInput;
        uniform ivec2 uMaskSize;
        layout(location = 0) out vec4 outColor;

        vec3 fetch(ivec2 p) {
            return texelFetch(uInput, clamp(p, ivec2(0), uMaskSize - ivec2(1)), 0).rgb;
        }

        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            vec3 sum = vec3(0.0);
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    float w = float((x == 0 ? 2 : 1) * (y == 0 ? 2 : 1));
                    sum += fetch(p + ivec2(x, y)) * w;
                }
            }
            outColor = vec4(sum / 16.0, 1.0);
        }
    """

    // ---- Composite: add the upsampled glow, then encode to sRGB ----
    private const val COMPOSITE_SHADER = """
        #version 300 es
        precision highp float;
        uniform sampler2D uInput;
        uniform sampler2D uMask;
        uniform ivec2 uImageSize;
        uniform ivec2 uMaskSize;
        uniform float uBloomAmount;
        layout(location = 0) out vec4 outColor;

        float encodeSrgb(float x) {
            x = clamp(x, 0.0, 1.0);
            return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 0.4166666667) - 0.055;
        }

        vec3 fetchMask(ivec2 p) {
            return texelFetch(uMask, clamp(p, ivec2(0), uMaskSize - ivec2(1)), 0).rgb;
        }

        // Same half-texel-centred bilinear the CPU bloom uses to upsample its mask.
        vec3 sampleMask(vec2 coord) {
            vec2 g = clamp(coord - vec2(0.5), vec2(0.0), vec2(uMaskSize) - 1.0);
            ivec2 low = ivec2(g);
            ivec2 high = min(low + ivec2(1), uMaskSize - ivec2(1));
            vec2 frac = g - vec2(low);
            vec3 top = mix(fetchMask(low), fetchMask(ivec2(high.x, low.y)), frac.x);
            vec3 bottom = mix(fetchMask(ivec2(low.x, high.y)), fetchMask(high), frac.x);
            return mix(top, bottom, frac.y);
        }

        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            vec3 value = texelFetch(uInput, p, 0).rgb;
            if (uBloomAmount > 0.0) {
                vec2 coord = (vec2(p) + 0.5) * vec2(uMaskSize) / vec2(uImageSize);
                value = max(value + sampleMask(coord) * 0.45 * uBloomAmount, 0.0);
            }
            outColor = vec4(encodeSrgb(value.r), encodeSrgb(value.g), encodeSrgb(value.b), 1.0);
        }
    """
}

/** Configuration passed from CPU to GPU shader. */
data class GpuPipelineConfig(
    val natural: Boolean,
    val wbGains: RgbGains,
    val colorMatrix: FloatArray,
    val warmth: Float,
    val exposureGain: Float,
    val midtoneGamma: Float,
    val contrast: Float,
    val highlightRollOffStart: Float,
    val highlightCompression: Float,
    val chromaStrength: Float,
    val lumaStrength: Float,
    val sharpenAmount: Float,
    /** StyleState.strength: scales the pad transforms and the style's own denoise. */
    val styleStrength: Float = 0f,
    /** StyleState.saturation, signed [-1, 1]. */
    val saturation: Float = 0f,
    /** StyleState.bloom, [0, 1]. */
    val bloom: Float = 0f,
    /** StyleEngine.ResolvedStyle, flattened. All zero/identity at neutral pads. */
    val toneContrast: Float = 0f,
    val midtoneLift: Float = 0f,
    val shoulderStrength: Float = 0f,
    val temperature: Float = 0f,
    val chromaScale: Float = 1f,
    val chromaCompression: Float = 0f,
    val paletteHueShift: Float = 0f,
    val paletteChromaBoost: Float = 0f,
    val shadowHueTint: Float = 0f,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        other is GpuPipelineConfig && natural == other.natural &&
        wbGains == other.wbGains &&
        warmth == other.warmth && exposureGain == other.exposureGain &&
        midtoneGamma == other.midtoneGamma && contrast == other.contrast &&
        highlightRollOffStart == other.highlightRollOffStart &&
        highlightCompression == other.highlightCompression &&
        chromaStrength == other.chromaStrength &&
        lumaStrength == other.lumaStrength &&
        sharpenAmount == other.sharpenAmount &&
        styleStrength == other.styleStrength && saturation == other.saturation &&
        bloom == other.bloom && toneContrast == other.toneContrast &&
        midtoneLift == other.midtoneLift && shoulderStrength == other.shoulderStrength &&
        temperature == other.temperature && chromaScale == other.chromaScale &&
        chromaCompression == other.chromaCompression &&
        paletteHueShift == other.paletteHueShift &&
        paletteChromaBoost == other.paletteChromaBoost &&
        shadowHueTint == other.shadowHueTint &&
        colorMatrix.contentEquals(other.colorMatrix)

    override fun hashCode(): Int = natural.hashCode() * 31 + wbGains.hashCode()
}
