package com.adin.naturalcam.image.raw

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import com.adin.naturalcam.image.core.CfaLayout
import com.adin.naturalcam.image.core.RawImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * GLES 3 RAW MFNR backend.
 *
 * The GPU owns proxy generation, bounded translation scoring, and CFA accumulation. The CPU
 * only chooses the best of 25 candidate translations and converts the final RG32F average back
 * to the existing RawImage contract. Any EGL, shader, framebuffer, or allocation failure returns
 * null so the caller uses the CPU reference implementation.
 */
internal object GlesRawBurstFusion {
    private const val TAG = "GlesRawBurstFusion"
    private const val PROXY_STEP = 4
    private const val MAX_SHIFT_CELLS = 2
    private const val CANDIDATE_COUNT = 25
    private const val MAX_EXPOSURE_SCALE = 4.0f
    private const val MAX_ALIGNMENT_RMSE = 0.08f
    private data class SessionKey(
        val width: Int,
        val height: Int,
        val cfa: CfaLayout,
        val whiteLevel: Int,
    )

    private val gpuExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "raw-gpu-fusion")
    }
    private var persistentSession: GlesSession? = null
    private var persistentKey: SessionKey? = null

    fun tryFuse(frames: List<RawImage>): RawBurstFusion.Result? {
        if (frames.size < 2) return null
        if (frames.any { !compatible(frames.first(), it) }) return null
        return try {
            gpuExecutor.submit<RawBurstFusion.Result> {
                val reference = frames.first()
                val key = SessionKey(
                    reference.width,
                    reference.height,
                    reference.metadata.cfa,
                    reference.metadata.whiteLevel,
                )
                if (persistentKey != key) {
                    persistentSession?.close()
                    persistentSession = GlesSession(reference)
                    persistentKey = key
                }
                persistentSession!!.fuse(frames)
            }.get()
        } catch (error: Exception) {
            Log.w(TAG, "GLES RAW fusion unavailable; using CPU fallback", error)
            persistentSession?.close()
            persistentSession = null
            persistentKey = null
            null
        } catch (error: LinkageError) {
            Log.w(TAG, "GLES RAW fusion linkage unavailable; using CPU fallback", error)
            persistentSession?.close()
            persistentSession = null
            persistentKey = null
            null
        }
    }


    private fun compatible(reference: RawImage, frame: RawImage): Boolean =
        reference.width == frame.width &&
            reference.height == frame.height &&
            reference.metadata.cfa == frame.metadata.cfa &&
            reference.metadata.whiteLevel == frame.metadata.whiteLevel


    private fun exposureScale(reference: RawImage, frame: RawImage): Float? {
        val referenceExposure = reference.metadata.exposureTimeNs
        val referenceIso = reference.metadata.isoSpeed
        val frameExposure = frame.metadata.exposureTimeNs
        val frameIso = frame.metadata.isoSpeed
        if (referenceExposure == null || referenceIso == null || frameExposure == null || frameIso == null) {
            return 1f
        }
        if (referenceExposure <= 0L || referenceIso <= 0 || frameExposure <= 0L || frameIso <= 0) {
            return null
        }
        val scale = (referenceExposure.toDouble() * referenceIso) /
            (frameExposure.toDouble() * frameIso)
        return scale.toFloat().takeIf {
            it.isFinite() && it in (1f / MAX_EXPOSURE_SCALE)..MAX_EXPOSURE_SCALE
        }
    }

    private class GlesSession(private val reference: RawImage) : AutoCloseable {
        private val width = reference.width
        private val height = reference.height
        private val proxyWidth = (width + PROXY_STEP - 1) / PROXY_STEP
        private val proxyHeight = (height + PROXY_STEP - 1) / PROXY_STEP
        private val referenceBlack = blackVector(reference)
        private val cfa = cfaCode(reference.metadata.cfa)
        private val whiteLevel = reference.metadata.whiteLevel.toFloat()

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var framebuffer = 0
        private var referenceRawTexture = 0
        private var currentRawTexture = 0
        private var referenceProxyTexture = 0
        private var currentProxyTexture = 0
        private var candidateTexture = 0
        private var accumulatorA = 0
        private var accumulatorB = 0
        private var accumulatorRead = 0
        private var proxyProgram = 0
        private var candidateProgram = 0
        private var mergeProgram = 0

        init {
            initializeEgl()
            initializeResources()
        }

        fun fuse(frames: List<RawImage>): RawBurstFusion.Result {
            uploadRaw(referenceRawTexture, reference)
            renderProxy(referenceRawTexture, referenceProxyTexture, referenceBlack, 1f)
            clearAccumulator()
            renderMerge(
                currentRawTexture = referenceRawTexture,
                offsetX = 0,
                offsetY = 0,
                scale = 1f,
                currentBlack = referenceBlack,
            )

            val errors = ArrayList<Float>(frames.size - 1)
            var merged = 1
            var skipped = 0
            for (frame in frames.drop(1)) {
                val scale = exposureScale(reference, frame)
                if (scale == null) {
                    skipped++
                    continue
                }
                uploadRaw(currentRawTexture, frame)
                val currentBlack = blackVector(frame)
                renderProxy(currentRawTexture, currentProxyTexture, currentBlack, scale)
                val alignment = findAlignment()
                errors += alignment.rmse
                if (!alignment.rmse.isFinite() || alignment.rmse > MAX_ALIGNMENT_RMSE) {
                    skipped++
                    continue
                }
                renderMerge(
                    currentRawTexture = currentRawTexture,
                    offsetX = alignment.dx * PROXY_STEP,
                    offsetY = alignment.dy * PROXY_STEP,
                    scale = scale,
                    currentBlack = currentBlack,
                )
                merged++
            }

            val output = readAccumulator()
            return RawBurstFusion.Result(
                image = RawImage(width, height, output, reference.metadata),
                mergedFrameCount = merged,
                skippedFrameCount = skipped,
                alignmentRmse = errors,
                backend = "gles3",
            )
        }

        override fun close() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            if (context != EGL14.EGL_NO_CONTEXT) {
                GLES30.glDeleteProgram(proxyProgram)
                GLES30.glDeleteProgram(candidateProgram)
                GLES30.glDeleteProgram(mergeProgram)
                GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
                GLES30.glDeleteTextures(
                    7,
                    intArrayOf(
                        referenceRawTexture,
                        currentRawTexture,
                        referenceProxyTexture,
                        currentProxyTexture,
                        candidateTexture,
                        accumulatorA,
                        accumulatorB,
                    ),
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
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
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
            framebuffer = createFramebuffer()
            referenceRawTexture = createTexture(GLES30.GL_R16UI, width, height)
            currentRawTexture = createTexture(GLES30.GL_R16UI, width, height)
            referenceProxyTexture = createTexture(GLES30.GL_R16F, proxyWidth, proxyHeight)
            currentProxyTexture = createTexture(GLES30.GL_R16F, proxyWidth, proxyHeight)
            candidateTexture = createTexture(GLES30.GL_R32F, CANDIDATE_COUNT, 1)
            accumulatorA = createTexture(GLES30.GL_RG32F, width, height)
            accumulatorB = createTexture(GLES30.GL_RG32F, width, height)
            proxyProgram = link(PROXY_FRAGMENT_SHADER)
            candidateProgram = link(CANDIDATE_FRAGMENT_SHADER)
            mergeProgram = link(MERGE_FRAGMENT_SHADER)
            checkFramebuffer(referenceProxyTexture)
            checkFramebuffer(candidateTexture)
            checkFramebuffer(accumulatorA)
        }

        private fun createFramebuffer(): Int {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            check(ids[0] != 0) { "Framebuffer allocation failed" }
            return ids[0]
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
            return ids[0]
        }

        private fun uploadRaw(texture: Int, image: RawImage) {
            val bytes = ByteBuffer
                .allocateDirect(image.pixelData.size * 2)
                .order(ByteOrder.nativeOrder())
            bytes.asShortBuffer().put(image.pixelData)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                0,
                0,
                image.width,
                image.height,
                GLES30.GL_RED_INTEGER,
                GLES30.GL_UNSIGNED_SHORT,
                bytes,
            )
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }

        private fun renderProxy(texture: Int, target: Int, black: FloatArray, scale: Float) {
            attach(target, proxyWidth, proxyHeight)
            GLES30.glViewport(0, 0, proxyWidth, proxyHeight)
            GLES30.glUseProgram(proxyProgram)
            bindTexture(proxyProgram, "uRaw", 0, texture)
            uniform4(proxyProgram, "uBlack", black)
            uniform1(proxyProgram, "uWhite", whiteLevel)
            uniform1(proxyProgram, "uExposureScale", scale)
            uniform1i(proxyProgram, "uCfa", cfa)
            draw()
        }

        private fun findAlignment(): Alignment {
            attach(candidateTexture, CANDIDATE_COUNT, 1)
            GLES30.glViewport(0, 0, CANDIDATE_COUNT, 1)
            GLES30.glUseProgram(candidateProgram)
            bindTexture(candidateProgram, "uReferenceProxy", 0, referenceProxyTexture)
            bindTexture(candidateProgram, "uCurrentProxy", 1, currentProxyTexture)
            uniform2i(candidateProgram, "uProxySize", proxyWidth, proxyHeight)
            draw()

            val bytes = ByteBuffer.allocateDirect(CANDIDATE_COUNT * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, CANDIDATE_COUNT, 1, GLES30.GL_RED, GLES30.GL_FLOAT, bytes)
            val values = bytes.asFloatBuffer()
            var bestIndex = 0
            var bestError = Float.POSITIVE_INFINITY
            for (index in 0 until CANDIDATE_COUNT) {
                val error = values.get(index)
                val dx = index % 5 - MAX_SHIFT_CELLS
                val dy = index / 5 - MAX_SHIFT_CELLS
                val bestDx = bestIndex % 5 - MAX_SHIFT_CELLS
                val bestDy = bestIndex / 5 - MAX_SHIFT_CELLS
                if (
                    error < bestError - 1e-6f ||
                    (abs(error - bestError) <= 1e-6f &&
                        abs(dx) + abs(dy) < abs(bestDx) + abs(bestDy))
                ) {
                    bestIndex = index
                    bestError = error
                }
            }
            return Alignment(
                dx = bestIndex % 5 - MAX_SHIFT_CELLS,
                dy = bestIndex / 5 - MAX_SHIFT_CELLS,
                rmse = sqrt(max(bestError, 0f)),
            )
        }

        private fun clearAccumulator() {
            attach(accumulatorA, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            accumulatorRead = accumulatorA
        }

        private fun renderMerge(
            currentRawTexture: Int,
            offsetX: Int,
            offsetY: Int,
            scale: Float,
            currentBlack: FloatArray,
        ) {
            val target = if (accumulatorRead == accumulatorA) accumulatorB else accumulatorA
            attach(target, width, height)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glUseProgram(mergeProgram)
            bindTexture(mergeProgram, "uAccumulator", 0, accumulatorRead)
            bindTexture(mergeProgram, "uRaw", 1, currentRawTexture)
            uniform4(mergeProgram, "uCurrentBlack", currentBlack)
            uniform4(mergeProgram, "uReferenceBlack", referenceBlack)
            uniform1(mergeProgram, "uWhite", whiteLevel)
            uniform1(mergeProgram, "uExposureScale", scale)
            uniform2i(mergeProgram, "uOffset", offsetX, offsetY)
            uniform1i(mergeProgram, "uCfa", cfa)
            draw()
            accumulatorRead = target
        }

        private fun readAccumulator(): ShortArray {
            attach(accumulatorRead, width, height)
            val bytes = ByteBuffer
                .allocateDirect(width * height * 2 * 4)
                .order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RG, GLES30.GL_FLOAT, bytes)
            val values = bytes.asFloatBuffer()
            val output = ShortArray(width * height)
            for (y in 0 until height) {
                val sourceY = y
                for (x in 0 until width) {
                    val sourceIndex = (sourceY * width + x) * 2
                    val sum = values.get(sourceIndex)
                    val count = values.get(sourceIndex + 1).coerceAtLeast(1f)
                    output[y * width + x] = (sum / count)
                        .roundToInt()
                        .coerceIn(0, reference.metadata.whiteLevel)
                        .toShort()
                }
            }
            return output
        }

        private fun attach(texture: Int, textureWidth: Int, textureHeight: Int) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                texture,
                0,
            )
            GLES30.glViewport(0, 0, textureWidth, textureHeight)
            checkFramebuffer(texture)
        }

        private fun checkFramebuffer(texture: Int) {
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "Framebuffer incomplete for texture=$texture"
            }
        }

        private fun draw() {
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glUseProgram(0)
        }

        private fun bindTexture(program: Int, name: String, unit: Int, texture: Int) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            uniform1i(program, name, unit)
        }

        private fun uniform1(program: Int, name: String, value: Float) {
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, name), value)
        }

        private fun uniform1i(program: Int, name: String, value: Int) {
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), value)
        }

        private fun uniform2i(program: Int, name: String, x: Int, y: Int) {
            GLES30.glUniform2i(GLES30.glGetUniformLocation(program, name), x, y)
        }

        private fun uniform4(program: Int, name: String, values: FloatArray) {
            GLES30.glUniform4fv(GLES30.glGetUniformLocation(program, name), 1, values, 0)
        }

        private fun link(fragmentSource: String): Int {
            val vertex = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES30.glCreateProgram()
            check(program != 0) { "GL program allocation failed" }
            GLES30.glAttachShader(program, vertex)
            GLES30.glAttachShader(program, fragment)
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
            check(status[0] != 0) { GLES30.glGetProgramInfoLog(program) }
            return program
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

    private data class Alignment(val dx: Int, val dy: Int, val rmse: Float)

    private fun blackVector(image: RawImage): FloatArray {
        val source = image.metadata.blackLevelPerChannel
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

    private const val PROXY_FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        precision highp int;
        precision highp usampler2D;
        uniform usampler2D uRaw;
        uniform vec4 uBlack;
        uniform float uWhite;
        uniform float uExposureScale;
        uniform int uCfa;
        layout(location = 0) out float outValue;

        int cfaChannel(ivec2 p) {
            int row = p.y - (p.y / 2) * 2;
            int col = p.x - (p.x / 2) * 2;
            if (uCfa == 0) return row == 0 ? (col == 0 ? 0 : 1) : (col == 0 ? 1 : 2);
            if (uCfa == 1) return row == 0 ? (col == 0 ? 1 : 0) : (col == 0 ? 2 : 1);
            if (uCfa == 2) return row == 0 ? (col == 0 ? 1 : 2) : (col == 0 ? 0 : 1);
            return row == 0 ? (col == 0 ? 2 : 1) : (col == 0 ? 1 : 0);
        }

        float blackAt(int channel) {
            return channel == 0 ? uBlack.r : (channel == 1 ? uBlack.g : uBlack.b);
        }

        void main() {
            ivec2 proxy = ivec2(gl_FragCoord.xy);
            ivec2 rawSize = textureSize(uRaw, 0);
            float sum = 0.0;
            float count = 0.0;
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    ivec2 p = proxy * 4 + ivec2(x, y);
                    if (p.x >= rawSize.x || p.y >= rawSize.y || cfaChannel(p) != 1) continue;
                    int channel = cfaChannel(p);
                    float black = blackAt(channel);
                    float range = max(uWhite - black, 1.0);
                    float value = float(texelFetch(uRaw, p, 0).r);
                    sum += clamp((value - black) / range, 0.0, 1.0) * uExposureScale;
                    count += 1.0;
                }
            }
            outValue = count > 0.0 ? sum / count : 0.0;
        }
    """

    private const val CANDIDATE_FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        precision highp int;
        uniform sampler2D uReferenceProxy;
        uniform sampler2D uCurrentProxy;
        uniform ivec2 uProxySize;
        layout(location = 0) out float outError;

        void main() {
            int candidate = int(gl_FragCoord.x);
            int dx = candidate - (candidate / 5) * 5 - 2;
            int dy = candidate / 5 - 2;
            int firstX = max(0, -dx);
            int lastX = min(uProxySize.x, uProxySize.x - dx);
            int firstY = max(0, -dy);
            int lastY = min(uProxySize.y, uProxySize.y - dy);
            float sum = 0.0;
            float count = 0.0;
            for (int y = firstY; y < lastY; y += 2) {
                for (int x = firstX; x < lastX; x += 2) {
                    float referenceValue = texelFetch(uReferenceProxy, ivec2(x, y), 0).r;
                    float currentValue = texelFetch(uCurrentProxy, ivec2(x + dx, y + dy), 0).r;
                    float difference = referenceValue - currentValue;
                    sum += difference * difference;
                    count += 1.0;
                }
            }
            outError = count > 0.0 ? sum / count : 1e9;
        }
    """

    private const val MERGE_FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        precision highp int;
        precision highp usampler2D;
        uniform sampler2D uAccumulator;
        uniform usampler2D uRaw;
        uniform vec4 uCurrentBlack;
        uniform vec4 uReferenceBlack;
        uniform float uWhite;
        uniform float uExposureScale;
        uniform ivec2 uOffset;
        uniform int uCfa;
        layout(location = 0) out vec2 outValue;

        int cfaChannel(ivec2 p) {
            int row = p.y - (p.y / 2) * 2;
            int col = p.x - (p.x / 2) * 2;
            if (uCfa == 0) return row == 0 ? (col == 0 ? 0 : 1) : (col == 0 ? 1 : 2);
            if (uCfa == 1) return row == 0 ? (col == 0 ? 1 : 0) : (col == 0 ? 2 : 1);
            if (uCfa == 2) return row == 0 ? (col == 0 ? 1 : 2) : (col == 0 ? 0 : 1);
            return row == 0 ? (col == 0 ? 2 : 1) : (col == 0 ? 1 : 0);
        }

        float blackAt(vec4 value, int channel) {
            return channel == 0 ? value.r : (channel == 1 ? value.g : value.b);
        }

        void main() {
            ivec2 outputPoint = ivec2(gl_FragCoord.xy);
            vec2 accumulated = texelFetch(uAccumulator, outputPoint, 0).rg;
            ivec2 sourcePoint = outputPoint + uOffset;
            ivec2 rawSize = textureSize(uRaw, 0);
            if (sourcePoint.x < 0 || sourcePoint.y < 0 ||
                sourcePoint.x >= rawSize.x || sourcePoint.y >= rawSize.y) {
                outValue = accumulated;
                return;
            }
            int channel = cfaChannel(sourcePoint);
            float currentBlack = blackAt(uCurrentBlack, channel);
            float referenceBlack = blackAt(uReferenceBlack, channel);
            float sampleValue = max(float(texelFetch(uRaw, sourcePoint, 0).r) - currentBlack, 0.0);
            sampleValue = referenceBlack + sampleValue * uExposureScale;
            outValue = vec2(accumulated.r + sampleValue, accumulated.g + 1.0);
        }
    """
}
