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
import com.adin.naturalcam.image.core.RgbImage
import com.adin.naturalcam.image.core.STYLE_CHROMA_DENOISE_STRENGTH
import com.adin.naturalcam.image.core.ProcessingConfiguration
import com.adin.naturalcam.image.core.naturalWarmth
import com.adin.naturalcam.image.processing.WhiteBalanceStage
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.StylePoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import java.util.concurrent.Executors

/**
 * Experimental end-to-end GPU path for PURE and style-neutral NATURAL.
 *
 * The RAW mosaic stays in GPU textures through normalization, lens shading, demosaic, metadata
 * white balance, camera calibration, residual WB, clipped-highlight neutralization, NATURAL tone,
 * local denoise/sharpen, gamut clamp, and sRGB output. Only a 32x32 WB reduction and the final
 * 8-bit frame cross the GPU boundary. Style workspace states fall back to the CPU reference.
 * Enable with `-PgpuPipelineExperimental=true`; the default is false.
 */
internal object GlesRawPurePipeline {
    private const val TAG = "GlesRawPurePipeline"
    private const val REDUCTION_SIZE = 32
    private const val READBACK_ROWS = 64

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "raw-gpu-pure")
    }
    private var session: Session? = null
    private var sessionKey: SessionKey? = null

    data class Result(val argb: IntArray, val width: Int, val height: Int)

    fun tryRender(raw: RawImage, config: ProcessingConfiguration): Result? {
        if (config.profile != ProcessingProfile.PURE && config.profile != ProcessingProfile.NATURAL) return null
        if (config.profile == ProcessingProfile.NATURAL) {
            val style = config.style
            if (
                style.tone != StylePoint.NEUTRAL ||
                style.color != StylePoint.NEUTRAL ||
                style.palette != StylePoint.NEUTRAL ||
                style.bloom != 0f ||
                style.grain != 0f ||
                style.saturation != 0f
            ) {
                Log.i(
                    TAG,
                    "NATURAL GPU skipped style tone=${style.tone.x}/${style.tone.y} " +
                        "color=${style.color.x}/${style.color.y} palette=${style.palette.x}/${style.palette.y} " +
                        "bloom=${style.bloom} grain=${style.grain} saturation=${style.saturation}",
                )
                return null
            }
        }
        val neutral = raw.metadata.asShotNeutral ?: run {
            Log.i(TAG, "${config.profile} GPU skipped: AsShotNeutral unavailable")
            return null
        }
        val matrix = runCatching { ColorMatrixFactory.cameraToWorkingRgbMatrix(raw.metadata) }
            .getOrNull() ?: run {
                Log.i(TAG, "${config.profile} GPU skipped: camera matrix unavailable")
                return null
            }
        val gains = runCatching { RgbGains.fromAsShotNeutral(neutral) }.getOrNull() ?: return null
        return try {
            val start = SystemClock.elapsedRealtime()
            val result = executor.submit<Result> {
                val key = SessionKey.from(raw)
                if (sessionKey != key) {
                    session?.close()
                    session = Session(raw)
                    sessionKey = key
                }
                session!!.render(raw, gains, matrix, config)
            }.get()
            Log.i(
                TAG,
                "${config.profile} GPU render ${result.width}x${result.height} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - start}",
            )
            result
        } catch (error: Exception) {
            Log.w(TAG, "experimental GLES ${config.profile} path unavailable; using CPU reference", error)
            reset()
            null
        } catch (error: LinkageError) {
            Log.w(TAG, "experimental GLES ${config.profile} path linkage unavailable; using CPU reference", error)
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
        val shadingColumns: Int,
        val shadingRows: Int,
    ) {
        companion object {
            fun from(raw: RawImage): SessionKey {
                val shading = raw.metadata.lensShading
                return SessionKey(
                    raw.width,
                    raw.height,
                    raw.metadata.cfa,
                    raw.metadata.whiteLevel,
                    shading?.columns ?: 1,
                    shading?.rows ?: 1,
                )
            }
        }
    }

    private class Session(private val reference: RawImage) : AutoCloseable {
        private val width = reference.width
        private val height = reference.height
        private val shading = reference.metadata.lensShading
        private val shadingColumns = shading?.columns ?: 1
        private val shadingRows = shading?.rows ?: 1

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var framebuffer = 0
        private var rawTexture = 0
        private var workingTexture = 0
        private var reductionTexture = 0
        private var outputTexture = 0
        private val shadingTextures = IntArray(4)
        private var rawProgram = 0
        private var reductionProgram = 0
        private var finalProgram = 0

        init {
            initializeEgl()
            initializeResources()
        }

        fun render(
            raw: RawImage,
            gains: RgbGains,
            matrix: FloatArray,
            config: ProcessingConfiguration,
        ): Result {
            uploadRaw(raw)
            renderWorking(raw, gains, matrix)
            val residual = readResidualWb()
            renderFinal(residual, config)
            return readFinal()
        }

        override fun close() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            if (context != EGL14.EGL_NO_CONTEXT) {
                GLES30.glDeleteProgram(rawProgram)
                GLES30.glDeleteProgram(reductionProgram)
                GLES30.glDeleteProgram(finalProgram)
                GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
                GLES30.glDeleteTextures(
                    4 + shadingTextures.size,
                    intArrayOf(rawTexture, workingTexture, reductionTexture, outputTexture, *shadingTextures),
                    0,
                )
            }
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
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
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] == 1) {
                "EGL ES3 config unavailable"
            }
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "EGL ES3 context unavailable" }
            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            check(surface != EGL14.EGL_NO_SURFACE) { "EGL pbuffer unavailable" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL make-current failed" }
        }

        private fun initializeResources() {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            check(ids[0] != 0) { "Framebuffer allocation failed" }
            framebuffer = ids[0]
            rawTexture = createTexture(GLES30.GL_R16UI, width, height)
            workingTexture = createTexture(GLES30.GL_RGBA32F, width, height)
            reductionTexture = createTexture(GLES30.GL_RGBA32F, REDUCTION_SIZE, REDUCTION_SIZE)
            outputTexture = createTexture(GLES30.GL_RGBA8, width, height)
            shadingTextures.forEachIndexed { index, _ ->
                shadingTextures[index] = createTexture(GLES30.GL_R32F, shadingColumns, shadingRows)
                uploadShading(shadingTextures[index], index)
            }
            rawProgram = link(RAW_SHADER)
            reductionProgram = link(REDUCTION_SHADER)
            finalProgram = link(FINAL_SHADER)
        }

        private fun createTexture(internalFormat: Int, textureWidth: Int, textureHeight: Int): Int {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            check(ids[0] != 0) { "Texture allocation failed" }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internalFormat, textureWidth, textureHeight)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            checkGlError("texture allocation")
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
            checkGlError("RAW upload")
        }

        private fun uploadShading(texture: Int, index: Int) {
            val values = shading?.grids?.get(index)?.copyOf()
                ?: FloatArray(shadingColumns * shadingRows) { 1f }
            val bytes = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            bytes.asFloatBuffer().put(values)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, shadingColumns, shadingRows,
                GLES30.GL_RED, GLES30.GL_FLOAT, bytes,
            )
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            checkGlError("lens shading upload")
        }

        private fun renderWorking(raw: RawImage, gains: RgbGains, matrix: FloatArray) {
            attach(workingTexture, width, height)
            GLES30.glUseProgram(rawProgram)
            bindTexture(rawProgram, "uRaw", 0, rawTexture)
            shadingTextures.forEachIndexed { index, texture ->
                bindTexture(rawProgram, "uShade$index", index + 1, texture)
            }
            uniform4(rawProgram, "uBlack", blackVector(raw))
            uniform1(rawProgram, "uWhite", raw.metadata.whiteLevel.toFloat())
            uniform1i(rawProgram, "uCfa", cfaCode(raw.metadata.cfa))
            uniform1i(rawProgram, "uHasShading", if (shading != null) 1 else 0)
            uniform2i(rawProgram, "uImageSize", width, height)
            uniform2i(rawProgram, "uGridSize", shadingColumns, shadingRows)
            uniform3(rawProgram, "uGains", floatArrayOf(gains.r, gains.g, gains.b))
            uniformMatrix3(rawProgram, "uMatrix", transpose(matrix))
            draw()
        }

        private fun readResidualWb(): RgbGains {
            attach(reductionTexture, REDUCTION_SIZE, REDUCTION_SIZE)
            GLES30.glUseProgram(reductionProgram)
            bindTexture(reductionProgram, "uWorking", 0, workingTexture)
            uniform2i(reductionProgram, "uReductionSize", REDUCTION_SIZE, REDUCTION_SIZE)
            draw()
            val bytes = ByteBuffer
                .allocateDirect(REDUCTION_SIZE * REDUCTION_SIZE * 4 * 4)
                .order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, REDUCTION_SIZE, REDUCTION_SIZE, GLES30.GL_RGBA, GLES30.GL_FLOAT, bytes)
            checkGlError("WB reduction readback")
            val values = bytes.asFloatBuffer()
            val sample = RgbImage(REDUCTION_SIZE, REDUCTION_SIZE)
            for (i in 0 until REDUCTION_SIZE * REDUCTION_SIZE) {
                val offset = i * 4
                sample.r[i] = values.get(offset)
                sample.g[i] = values.get(offset + 1)
                sample.b[i] = values.get(offset + 2)
            }
            return WhiteBalanceStage.auto(sample)
        }

        private fun renderFinal(gains: RgbGains, config: ProcessingConfiguration) {
            attach(outputTexture, width, height)
            GLES30.glUseProgram(finalProgram)
            bindTexture(finalProgram, "uWorking", 0, workingTexture)
            uniform3(finalProgram, "uGains", floatArrayOf(gains.r, gains.g, gains.b))
            val natural = config.profile == ProcessingProfile.NATURAL
            uniform1i(finalProgram, "uProfile", if (natural) 1 else 0)
            uniform1(finalProgram, "uWarmth", if (natural) naturalWarmth(config.tone) else 0f)
            uniform1(finalProgram, "uExposureGain", if (natural) 2f.pow(config.tone.exposureStops) else 1f)
            uniform1(finalProgram, "uToneGamma", if (natural) config.tone.midtoneGamma else 1f)
            uniform1(finalProgram, "uToneContrast", if (natural) config.tone.contrast else 0f)
            uniform1(finalProgram, "uRollStart", if (natural) config.tone.highlightRollOffStart else 1f)
            uniform1(finalProgram, "uRollCompression", if (natural) config.tone.highlightCompression else 0f)
            uniform1(
                finalProgram,
                "uChromaStrength",
                if (natural) config.chromaSmoothStrength +
                    STYLE_CHROMA_DENOISE_STRENGTH * config.style.strength.coerceIn(0f, 1f)
                else 0f,
            )
            uniform1(finalProgram, "uLumaStrength", if (natural) config.lumaSmoothStrength else 0f)
            uniform1(finalProgram, "uSharpenAmount", if (natural) config.sharpenAmount else 0f)
            draw()
        }

        private fun readFinal(): Result {
            attach(outputTexture, width, height)
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
            checkGlError("final readback")
            return Result(argb, width, height)
        }

        private fun attach(texture: Int, textureWidth: Int, textureHeight: Int) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0,
            )
            GLES30.glViewport(0, 0, textureWidth, textureHeight)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "Framebuffer incomplete for texture=$texture"
            }
        }

        private fun draw() {
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glUseProgram(0)
            checkGlError("GPU draw")
        }

        private fun bindTexture(program: Int, name: String, unit: Int, texture: Int) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            uniform1i(program, name, unit)
        }

        private fun uniform1(program: Int, name: String, value: Float) =
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, name), value)

        private fun uniform1i(program: Int, name: String, value: Int) =
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), value)

        private fun uniform2i(program: Int, name: String, x: Int, y: Int) =
            GLES30.glUniform2i(GLES30.glGetUniformLocation(program, name), x, y)

        private fun uniform3(program: Int, name: String, values: FloatArray) =
            GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, name), 1, values, 0)

        private fun uniform4(program: Int, name: String, values: FloatArray) =
            GLES30.glUniform4fv(GLES30.glGetUniformLocation(program, name), 1, values, 0)

        private fun uniformMatrix3(program: Int, name: String, values: FloatArray) =
            GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(program, name), 1, false, values, 0)

        private fun link(fragmentSource: String): Int {
            val vertex = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            val linked = GLES30.glCreateProgram()
            check(linked != 0) { "GL program allocation failed" }
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
            check(shader != 0) { "GL shader allocation failed" }
            GLES30.glShaderSource(shader, source.trimIndent())
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES30.glGetShaderInfoLog(shader) }
            return shader
        }
    }

    private fun transpose(matrix: FloatArray): FloatArray = FloatArray(9) { index ->
        matrix[(index % 3) * 3 + index / 3]
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

    private fun checkGlError(stage: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "$stage failed: 0x${error.toString(16)}" }
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

    private const val RAW_SHADER = """
        #version 300 es
        precision highp float;
        precision highp int;
        precision highp usampler2D;
        uniform usampler2D uRaw;
        uniform sampler2D uShade0;
        uniform sampler2D uShade1;
        uniform sampler2D uShade2;
        uniform sampler2D uShade3;
        uniform vec4 uBlack;
        uniform float uWhite;
        uniform int uCfa;
        uniform int uHasShading;
        uniform ivec2 uImageSize;
        uniform ivec2 uGridSize;
        uniform vec3 uGains;
        uniform mat3 uMatrix;
        layout(location = 0) out vec4 outColor;
        const float MISSING = -1.0;
        int parity(int value) { return value - (value / 2) * 2; }
        int cfaChannel(ivec2 p) {
            int row = parity(p.y); int col = parity(p.x);
            if (uCfa == 0) return row == 0 ? (col == 0 ? 0 : 1) : (col == 0 ? 1 : 2);
            if (uCfa == 1) return row == 0 ? (col == 0 ? 1 : 0) : (col == 0 ? 2 : 1);
            if (uCfa == 2) return row == 0 ? (col == 0 ? 1 : 2) : (col == 0 ? 0 : 1);
            return row == 0 ? (col == 0 ? 2 : 1) : (col == 0 ? 1 : 0);
        }
        float blackAt(int channel) { return channel == 0 ? uBlack.r : (channel == 1 ? uBlack.g : uBlack.b); }
        bool inside(ivec2 p) { return p.x >= 0 && p.y >= 0 && p.x < uImageSize.x && p.y < uImageSize.y; }
        float shadeAt(ivec2 p) {
            if (uHasShading == 0) return 1.0;
            vec2 grid = vec2(p) * (vec2(uGridSize) - 1.0) / max(vec2(uImageSize) - 1.0, vec2(1.0));
            vec2 uv = (grid + 0.5) / vec2(uGridSize);
            int phase = parity(p.y) * 2 + parity(p.x);
            if (phase == 0) return texture(uShade0, uv).r;
            if (phase == 1) return texture(uShade1, uv).r;
            if (phase == 2) return texture(uShade2, uv).r;
            return texture(uShade3, uv).r;
        }
        float sampleAt(ivec2 point) {
            ivec2 p = clamp(point, ivec2(0), uImageSize - ivec2(1));
            int channel = cfaChannel(p);
            float black = blackAt(channel);
            float range = max(uWhite - black, 1.0);
            return (float(texelFetch(uRaw, p, 0).r) - black) / range * shadeAt(p);
        }
        float probe(ivec2 p, int channel, ivec2 direction) {
            for (int distance = 1; distance <= 2; distance++) {
                ivec2 q = p + direction * distance;
                if (!inside(q)) return MISSING;
                if (cfaChannel(q) == channel) return sampleAt(q);
            }
            return MISSING;
        }
        float horizontalNearest(ivec2 p, int channel) {
            float left = probe(p, channel, ivec2(-1, 0)); float right = probe(p, channel, ivec2(1, 0));
            if (left > -0.5 && right > -0.5) return (left + right) * 0.5;
            if (left > -0.5) return left;
            return right;
        }
        float verticalNearest(ivec2 p, int channel) {
            float up = probe(p, channel, ivec2(0, -1)); float down = probe(p, channel, ivec2(0, 1));
            if (up > -0.5 && down > -0.5) return (up + down) * 0.5;
            if (up > -0.5) return up;
            return down;
        }
        vec2 diagonalSum(ivec2 p, int channel) {
            float total = 0.0; float count = 0.0; ivec2 q;
            q = p + ivec2(-1, -1); if (inside(q) && cfaChannel(q) == channel) { total += sampleAt(q); count += 1.0; }
            q = p + ivec2(1, -1); if (inside(q) && cfaChannel(q) == channel) { total += sampleAt(q); count += 1.0; }
            q = p + ivec2(-1, 1); if (inside(q) && cfaChannel(q) == channel) { total += sampleAt(q); count += 1.0; }
            q = p + ivec2(1, 1); if (inside(q) && cfaChannel(q) == channel) { total += sampleAt(q); count += 1.0; }
            return vec2(total, count);
        }
        float fallback(ivec2 p, int channel) {
            float horizontal = horizontalNearest(p, channel); if (horizontal > -0.5) return horizontal;
            float vertical = verticalNearest(p, channel); if (vertical > -0.5) return vertical;
            return sampleAt(p);
        }
        float crossValue(ivec2 p, int channel) {
            float total = 0.0; float count = 0.0; ivec2 q;
            q = p + ivec2(-1, 0); if (inside(q) && cfaChannel(q) == channel) { total += sampleAt(q); count += 1.0; }
            q = p + ivec2(1, 0); if (inside(q) && cfaChannel(q) == channel) { total += sampleAt(q); count += 1.0; }
            q = p + ivec2(0, -1); if (inside(q) && cfaChannel(q) == channel) { total += sampleAt(q); count += 1.0; }
            q = p + ivec2(0, 1); if (inside(q) && cfaChannel(q) == channel) { total += sampleAt(q); count += 1.0; }
            return count > 0.0 ? total / count : fallback(p, channel);
        }
        float diagonal(ivec2 p, int channel) { vec2 sum = diagonalSum(p, channel); return sum.y > 0.0 ? sum.x / sum.y : fallback(p, channel); }
        float chromaAtGreen(ivec2 p, int channel) {
            float total = 0.0; float count = 0.0; float horizontal = horizontalNearest(p, channel);
            if (horizontal > -0.5) { total += horizontal; count += 1.0; }
            float vertical = verticalNearest(p, channel); if (vertical > -0.5) { total += vertical; count += 1.0; }
            vec2 diagonalValue = diagonalSum(p, channel); if (diagonalValue.y > 0.0) { total += diagonalValue.x / diagonalValue.y; count += 1.0; }
            return count > 0.0 ? total / count : sampleAt(p);
        }
        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy); int own = cfaChannel(p); float red; float green; float blue;
            if (own == 0) { red = sampleAt(p); green = crossValue(p, 1); blue = diagonal(p, 2); }
            else if (own == 2) { blue = sampleAt(p); green = crossValue(p, 1); red = diagonal(p, 0); }
            else { green = sampleAt(p); red = chromaAtGreen(p, 0); blue = chromaAtGreen(p, 2); }
            outColor = vec4(uMatrix * (vec3(red, green, blue) * uGains), 1.0);
        }
    """

    private const val REDUCTION_SHADER = """
        #version 300 es
        precision highp float;
        uniform sampler2D uWorking;
        uniform ivec2 uReductionSize;
        layout(location = 0) out vec4 outColor;
        void main() {
            ivec2 sourceSize = textureSize(uWorking, 0);
            ivec2 outputPoint = ivec2(gl_FragCoord.xy);
            int firstX = (outputPoint.x * sourceSize.x) / uReductionSize.x;
            int lastX = max(firstX + 1, ((outputPoint.x + 1) * sourceSize.x) / uReductionSize.x);
            int firstY = (outputPoint.y * sourceSize.y) / uReductionSize.y;
            int lastY = max(firstY + 1, ((outputPoint.y + 1) * sourceSize.y) / uReductionSize.y);
            vec3 sum = vec3(0.0); float count = 0.0;
            for (int y = firstY; y < lastY; y++) for (int x = firstX; x < lastX; x++) {
                sum += texelFetch(uWorking, ivec2(x, y), 0).rgb; count += 1.0;
            }
            outColor = vec4(sum / max(count, 1.0), 1.0);
        }
    """

    private const val FINAL_SHADER = """
        #version 300 es
        precision highp float;
        precision highp int;
        uniform sampler2D uWorking;
        uniform vec3 uGains;
        uniform int uProfile;
        uniform float uWarmth;
        uniform float uExposureGain;
        uniform float uToneGamma;
        uniform float uToneContrast;
        uniform float uRollStart;
        uniform float uRollCompression;
        uniform float uChromaStrength;
        uniform float uLumaStrength;
        uniform float uSharpenAmount;
        layout(location = 0) out vec4 outColor;

        float luminance(vec3 value) {
            return value.r * 0.2126 + value.g * 0.7152 + value.b * 0.0722;
        }

        vec3 workingAt(ivec2 p) {
            ivec2 size = textureSize(uWorking, 0);
            return texelFetch(uWorking, clamp(p, ivec2(0), size - ivec2(1)), 0).rgb * uGains;
        }

        vec3 neutralizeClipped(vec3 value) {
            float minimum = min(value.r, min(value.g, value.b));
            float maximum = max(value.r, max(value.g, value.b));
            float middle = value.r + value.g + value.b - minimum - maximum;
            if (minimum > 0.6 && middle > 0.95) {
                float amount = clamp((middle - 0.95) / 0.25, 0.0, 1.0);
                float neutral = (value.r + value.g + value.b) / 3.0;
                return mix(value, vec3(neutral), amount);
            }
            return value;
        }

        vec3 naturalMap(ivec2 p) {
            vec3 value = neutralizeClipped(workingAt(p));
            value *= vec3(1.0 + uWarmth, 1.0, 1.0 - uWarmth) * uExposureGain;
            float sourceLuma = luminance(value);
            if (sourceLuma > 0.0) {
                float mapped = sourceLuma <= 0.18
                    ? 0.18 * pow(max(sourceLuma / 0.18, 0.0), uToneGamma)
                    : 0.18 * pow(max(sourceLuma / 0.18, 0.0), 1.0 + uToneContrast);
                value *= mapped / sourceLuma;
            }
            float maximum = max(value.r, max(value.g, value.b));
            if (maximum > uRollStart && uRollStart < 1.0) {
                float span = 1.0 - uRollStart;
                float t = clamp((maximum - uRollStart) / span, 0.0, 1.0);
                float exponent = 1.0 + uRollCompression * 0.75;
                float mapped = uRollStart + span * pow(t, exponent);
                value *= mapped / maximum;
            }
            return value;
        }

        vec3 naturalFiltered(ivec2 p) {
            vec3 center = naturalMap(p);
            float centerLuma = luminance(center);
            float blurLuma = 0.0;
            float blurWeight = 0.0;
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    float weight = float((x == 0 ? 2 : 1) * (y == 0 ? 2 : 1));
                    blurLuma += luminance(naturalMap(p + ivec2(x, y))) * weight;
                    blurWeight += weight;
                }
            }
            blurLuma /= blurWeight;

            vec3 mean = vec3(0.0);
            for (int y = 0; y <= 1; y++) {
                for (int x = 0; x <= 1; x++) mean += naturalMap(p + ivec2(x, y));
            }
            mean *= 0.25;
            float meanLuma = luminance(mean);
            vec3 centerChroma = center - vec3(centerLuma);
            vec3 meanChroma = mean - vec3(meanLuma);
            float outputLuma = centerLuma + uLumaStrength * (blurLuma - centerLuma);
            vec3 value = vec3(outputLuma) +
                centerChroma + uChromaStrength * (meanChroma - centerChroma);
            value += vec3((centerLuma - blurLuma) * uSharpenAmount);
            return value;
        }

        float encode(float x) {
            x = clamp(x, 0.0, 1.0);
            return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 0.4166666667) - 0.055;
        }

        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            vec3 value = uProfile == 1 ? naturalFiltered(p) : neutralizeClipped(workingAt(p));
            outColor = vec4(encode(value.r), encode(value.g), encode(value.b), 1.0);
        }
    """
}
