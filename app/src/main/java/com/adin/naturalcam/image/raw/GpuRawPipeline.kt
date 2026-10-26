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
import com.adin.naturalcam.image.core.RawCaptureMetadata
import com.adin.naturalcam.image.core.RawImage
import com.adin.naturalcam.image.core.RgbGains
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * GPU-native RAW pipeline.
 *
 * Uploads the RAW mosaic once, then runs the full processing chain as GPU shaders
 * on ping-pong RGBA16F textures. The only readback is the final 8-bit ARGB frame.
 * No intermediate full-resolution data crosses the GPU/CPU boundary.
 *
 * Supported contract:
 * - RAW sensor input (16-bit CFA mosaic)
 * - NATURAL and PURE profiles
 * - AsShotNeutral + camera matrix required
 * - Style workspace falls back to CPU
 */
internal object GpuRawPipeline {
    private const val TAG = "GpuRawPipeline"
    private const val READBACK_ROWS = 64

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

        // Ping-pong float textures for multi-pass processing
        private var textureA = 0
        private var textureB = 0
        private var rawTexture = 0
        private var outputTexture = 0

        private var rawProgram = 0

        init {
            initializeEgl()
            initializeResources()
        }

        fun render(raw: RawImage, config: GpuPipelineConfig): Result {
            uploadRaw(raw)
            renderRawToLinear(raw, config)
            if (config.natural) {
                renderNatural(config)
            }
            return readOutput()
        }

        override fun close() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            if (context != EGL14.EGL_NO_CONTEXT) {
                GLES30.glDeleteProgram(rawProgram)
                GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
                GLES30.glDeleteTextures(4, intArrayOf(rawTexture, textureA, textureB, outputTexture), 0)
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

            rawProgram = link(FULL_RAW_SHADER)
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

        private fun renderRawToLinear(raw: RawImage, config: GpuPipelineConfig) {
            attach(textureA)
            GLES30.glUseProgram(rawProgram)
            bindTexture(rawProgram, "uRaw", 0, rawTexture)
            uniform4f(rawProgram, "uBlack", blackVector(raw))
            uniform1f(rawProgram, "uWhite", raw.metadata.whiteLevel.toFloat())
            uniform1i(rawProgram, "uCfa", cfaCode(raw.metadata.cfa))
            uniform2i(rawProgram, "uImageSize", width, height)
            uniform3f(rawProgram, "uGains", floatArrayOf(config.wbGains.r, config.wbGains.g, config.wbGains.b))
            uniformMatrix3(rawProgram, "uMatrix", config.colorMatrix)
            uniform1i(rawProgram, "uNatural", if (config.natural) 1 else 0)
            uniform1f(rawProgram, "uWarmth", config.warmth)
            uniform1f(rawProgram, "uExposureGain", config.exposureGain)
            uniform1f(rawProgram, "uToneGamma", config.midtoneGamma)
            uniform1f(rawProgram, "uToneContrast", config.contrast)
            uniform1f(rawProgram, "uRollStart", config.highlightRollOffStart)
            uniform1f(rawProgram, "uRollCompression", config.highlightCompression)
            uniform1f(rawProgram, "uChromaStrength", config.chromaStrength)
            uniform1f(rawProgram, "uLumaStrength", config.lumaStrength)
            uniform1f(rawProgram, "uSharpenAmount", config.sharpenAmount)
            drawFullscreen()
        }

        private fun renderNatural(config: GpuPipelineConfig) {
            // NATURAL base is already applied in rawProgram; nothing extra needed here.
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

        private fun attach(texture: Int) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0,
            )
            GLES30.glViewport(0, 0, width, height)
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

    private const val FULL_RAW_SHADER = """
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
        uniform int uNatural;
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

        float luma(vec3 c) { return c.r * 0.2126 + c.g * 0.7152 + c.b * 0.0722; }

        float toneMap(float x) {
            if (x <= 0.0) return 0.0;
            float l = x;
            if (l > 0.0) {
                float mapped = l <= 0.18
                    ? 0.18 * pow(max(l / 0.18, 0.0), uToneGamma)
                    : 0.18 * pow(max(l / 0.18, 0.0), 1.0 + uToneContrast);
                return mapped;
            }
            return l;
        }

        vec3 processPixel(ivec2 p) {
            vec3 value = demosaic(p);

            // WB + color matrix
            value *= uGains;
            value = uMatrix * value;

            if (uNatural == 1) {
                // Warmth
                value *= vec3(1.0 + uWarmth, 1.0, 1.0 - uWarmth);
                // Exposure
                value *= uExposureGain;

                float l = luma(value);
                if (l > 0.0) {
                    float mapped = toneMap(l);
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

                // Denoise (2x2 chroma + luma)
                if (uChromaStrength > 0.0 || uLumaStrength > 0.0) {
                    vec3 mean = vec3(0.0);
                    for (int y = 0; y <= 1; y++) {
                        for (int x = 0; x <= 1; x++) mean += demosaic(p + ivec2(x, y));
                    }
                    mean *= 0.25;
                    float meanL = luma(mean);
                    float srcL = luma(value);
                    vec3 srcC = value - vec3(srcL);
                    vec3 meanC = mean - vec3(meanL);
                    float outL = srcL + uLumaStrength * (meanL - srcL);
                    value = vec3(outL) + srcC + uChromaStrength * (meanC - srcC);
                }

                // Sharpen
                if (uSharpenAmount > 0.0) {
                    float centerL = luma(value);
                    float blurL = 0.0;
                    float blurW = 0.0;
                    for (int y = -1; y <= 1; y++) {
                        for (int x = -1; x <= 1; x++) {
                            float w = float((x == 0 ? 2 : 1) * (y == 0 ? 2 : 1));
                            blurL += luma(demosaic(p + ivec2(x, y))) * w;
                            blurW += w;
                        }
                    }
                    blurL /= blurW;
                    value += vec3((centerL - blurL) * uSharpenAmount);
                }
            }

            // Clipped highlight neutralization (for PURE too)
            float mn2 = min(value.r, min(value.g, value.b));
            float md2 = value.r + value.g + value.b - mn2 - max(value.r, max(value.g, value.b));
            if (mn2 > 0.6 && md2 > 0.95) {
                float amt2 = clamp((md2 - 0.95) / 0.25, 0.0, 1.0);
                float neutral2 = (value.r + value.g + value.b) / 3.0;
                value = mix(value, vec3(neutral2), amt2);
            }

            return value;
        }

        float encodeSrgb(float x) {
            x = clamp(x, 0.0, 1.0);
            return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 0.4166666667) - 0.055;
        }

        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            vec3 value = processPixel(p);
            outColor = vec4(
                encodeSrgb(value.r),
                encodeSrgb(value.g),
                encodeSrgb(value.b),
                1.0
            );
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
        colorMatrix.contentEquals(other.colorMatrix)

    override fun hashCode(): Int = natural.hashCode() * 31 + wbGains.hashCode()
}
