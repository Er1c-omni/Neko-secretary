package com.neko.secretary.live2d

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log

/**
 * Minimal EGL14 wrapper for the dedicated Live2D GL thread.
 *
 * - RGBA8888 config with an 8-bit alpha channel so TextureView can composite the character over
 *   third-party apps (TextureView.isOpaque = false, premultiplied alpha output).
 * - Keeps the EGLContext alive across window-surface churn (drag, stealth badge, screen off) by
 *   parking it on a 1x1 pbuffer, so Cubism textures/shaders don't need to be re-uploaded.
 * - Must only be touched from the thread that created it.
 */
internal class EglCore {
    var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var parkingSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    val isReady: Boolean get() = context != EGL14.EGL_NO_CONTEXT

    fun initialize() {
        if (isReady) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed: ${errorString()}" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "eglChooseConfig RGBA8888 failed: ${errorString()}"
        }
        config = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${errorString()}" }

        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        parkingSurface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
        check(parkingSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed: ${errorString()}" }
        makeCurrent(parkingSurface)
    }

    fun createWindowSurface(surfaceTexture: SurfaceTexture): EGLSurface {
        val surface = EGL14.eglCreateWindowSurface(display, config, surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${errorString()}" }
        return surface
    }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed: ${errorString()}" }
    }

    /** Detach [surface] and keep the context current on the pbuffer so GL resources survive. */
    fun releaseWindowSurface(surface: EGLSurface) {
        if (surface == EGL14.EGL_NO_SURFACE) return
        if (parkingSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, parkingSurface, parkingSurface, context)
        } else {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
        EGL14.eglDestroySurface(display, surface)
    }

    /** @return EGL14.EGL_SUCCESS or the eglGetError() code (e.g. EGL_CONTEXT_LOST, EGL_BAD_SURFACE). */
    fun swapBuffers(surface: EGLSurface): Int =
        if (EGL14.eglSwapBuffers(display, surface)) EGL14.EGL_SUCCESS else EGL14.eglGetError()

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (parkingSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, parkingSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        parkingSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        config = null
    }

    private fun errorString(): String = "0x" + Integer.toHexString(EGL14.eglGetError())

    companion object {
        fun logError(tag: String, message: String) = Log.w(tag, message)
    }
}
