package com.adin.naturalcam.image.raw

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import com.adin.naturalcam.image.core.CfaLayout
import com.adin.naturalcam.image.core.RawCaptureMetadata
import com.adin.naturalcam.image.core.RawImage

/** Debug-only capability probe independent of RAW capture and shader fusion. */
internal object GlesCapabilityProbe {
    private const val TAG = "GlesCapabilityProbe"

    fun run() {
        var available = false
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            val major = IntArray(1)
            val minor = IntArray(1)
            check(EGL14.eglInitialize(display, major, 0, minor, 0)) {
                "eglInitialize failed error=${eglError()}"
            }

            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] == 1) {
                "eglChooseConfig failed error=${eglError()}"
            }
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) {
                "eglCreateContext failed error=${eglError()}"
            }
            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            check(surface != EGL14.EGL_NO_SURFACE) {
                "eglCreatePbufferSurface failed error=${eglError()}"
            }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "eglMakeCurrent failed error=${eglError()}"
            }

            val maxTexture = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            Log.i(
                TAG,
                "success egl=${major[0]}.${minor[0]} " +
                    "gl=${GLES30.glGetString(GLES30.GL_VERSION)} " +
                    "renderer=${GLES30.glGetString(GLES30.GL_RENDERER)} " +
                    "maxTexture=${maxTexture[0]}",
            )
            available = true
        } catch (error: Throwable) {
            Log.e(TAG, "failed error=${eglError()} ${error.message}", error)
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
        if (available) runFusionSmoke()
    }
    private fun runFusionSmoke() {
        try {
            val metadata = RawCaptureMetadata(
                cfa = CfaLayout.RGGB,
                blackLevelPerChannel = floatArrayOf(0f, 0f, 0f),
                whiteLevel = 1023,
                colorMatrix1 = null,
                colorMatrix2 = null,
                forwardMatrix1 = null,
                forwardMatrix2 = null,
                asShotNeutral = floatArrayOf(1f, 1f, 1f),
                isoSpeed = 100,
                exposureTimeNs = 1_000_000L,
                aperture = null,
                focalLengthMm = null,
                orientationDegrees = 0,
                timestampMs = 0L,
            )
            val pixels = ShortArray(64) { (100 + it).toShort() }
            val frames = listOf(
                RawImage(8, 8, pixels, metadata),
                RawImage(8, 8, pixels.copyOf(), metadata),
            )
            val result = GlesRawBurstFusion.tryFuse(frames)
            Log.i(
                TAG,
                "fusionSmoke backend=${result?.backend ?: "unavailable"} " +
                    "merged=${result?.mergedFrameCount ?: 0}",
            )
        } catch (error: Throwable) {
            Log.e(TAG, "fusionSmoke failed ${error.message}", error)
        }
    }


    private fun eglError(): String = "0x${EGL14.eglGetError().toString(16)}"
}
