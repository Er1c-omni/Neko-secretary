package com.neko.secretary.live2d

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dedicated GL thread for the transparent TextureView companion.
 *
 * Lifecycle contract (all public methods are thread-safe, everything GL happens on this thread):
 * - [onSurfaceAvailable] / [onSurfaceSizeChanged] come from the UI thread's TextureView listener.
 * - [onSurfaceDestroyed] BLOCKS (bounded) until the EGL window surface is destroyed, so we never
 *   swap into a SurfaceTexture that TextureView has already released.
 * - The EGLContext survives surface churn (parked on a pbuffer), so model textures are uploaded once.
 * - targetFps == 0 parks the loop on a monitor wait: zero GL calls, zero wakeups, GPU idle.
 * - EGL_CONTEXT_LOST triggers a full teardown + transparent re-init of the backend.
 */
internal class Live2DRenderThread(
    private val backendProvider: () -> Live2DModelBackend,
    private val callbacks: Callbacks
) : Thread("Neko-Live2D-GL") {

    interface Callbacks {
        fun onHitMask(mask: HitTestMask)
        fun onFpsMeasured(fps: Int)
        fun onMotionActiveChanged(active: Boolean)
        fun onBackendReady(name: String)
    }

    private val lock = Object()

    // ---- guarded by lock ----
    private var pendingTexture: SurfaceTexture? = null
    private var width = 0
    private var height = 0
    private var sizeDirty = false
    private var targetFps = FramePacingPolicy.FPS_PEEK
    private var renderOnce = false
    private var exitRequested = false
    private var releaseSurfaceRequested = false
    private var surfaceReleasedAck = true
    private var hitMaskRequested = true
    private var hitMaskSuppressed = false
    private var backendReplaceRequested = false
    private val commands = ArrayList<(Live2DModelBackend) -> Unit>()

    // ---- GL thread only ----
    private val egl = EglCore()
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var attachedTexture: SurfaceTexture? = null
    private var backend: Live2DModelBackend? = null
    private var backendGlReady = false
    private var lastFrameNanos = 0L
    private var lastMaskNanos = 0L
    private var fpsWindowStart = 0L
    private var framesInWindow = 0
    private var reportedFps = -1
    private var reportedMotion = false
    private var readbackBuffer: ByteBuffer? = null

    fun onSurfaceAvailable(texture: SurfaceTexture, w: Int, h: Int) = synchronized(lock) {
        pendingTexture = texture
        width = w
        height = h
        sizeDirty = true
        renderOnce = true
        hitMaskRequested = true
        releaseSurfaceRequested = false
        lock.notifyAll()
    }

    fun onSurfaceSizeChanged(w: Int, h: Int) = synchronized(lock) {
        width = w
        height = h
        sizeDirty = true
        renderOnce = true
        hitMaskRequested = true
        lock.notifyAll()
    }

    /** @return true when the GL thread acknowledged the surface release within [timeoutMs]. */
    fun onSurfaceDestroyed(timeoutMs: Long = 500L): Boolean = synchronized(lock) {
        pendingTexture = null
        releaseSurfaceRequested = true
        surfaceReleasedAck = false
        lock.notifyAll()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!surfaceReleasedAck && isAlive) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            lock.wait(remaining)
        }
        surfaceReleasedAck
    }

    fun setTargetFps(fps: Int) = synchronized(lock) {
        val clamped = fps.coerceIn(0, FramePacingPolicy.FPS_INTERACTIVE)
        if (clamped != targetFps) {
            targetFps = clamped
            lock.notifyAll()
        }
    }

    /** Commands are applied on the next rendered frame (not forcing a wake-up while parked). */
    fun enqueue(command: (Live2DModelBackend) -> Unit) = synchronized(lock) {
        commands.add(command)
        if (targetFps > 0) lock.notifyAll()
    }

    /** Render a single frame even at 0 FPS (e.g. to refresh the cached frame before peeking). */
    fun requestRender() = synchronized(lock) {
        renderOnce = true
        lock.notifyAll()
    }

    fun requestHitMask() = synchronized(lock) { hitMaskRequested = true }

    /** Skip glReadPixels while dragging: the silhouette does not change and stalls cost frames. */
    fun setHitMaskSuppressed(suppressed: Boolean) = synchronized(lock) { hitMaskSuppressed = suppressed }

    fun replaceBackend() = synchronized(lock) {
        backendReplaceRequested = true
        renderOnce = true
        lock.notifyAll()
    }

    fun requestExitAndWait(timeoutMs: Long = 1_000L) {
        synchronized(lock) {
            exitRequested = true
            lock.notifyAll()
        }
        try {
            join(timeoutMs)
        } catch (_: InterruptedException) {
            currentThread().interrupt()
        }
    }

    override fun run() {
        try {
            loop()
        } catch (t: Throwable) {
            Log.e(TAG, "GL thread crashed", t)
        } finally {
            teardownGl()
            synchronized(lock) {
                surfaceReleasedAck = true
                lock.notifyAll()
            }
            callbacks.onFpsMeasured(0)
        }
    }

    private fun loop() {
        while (true) {
            val texture: SurfaceTexture
            val w: Int
            val h: Int
            var sizeChanged: Boolean
            val fps: Int
            val maskWanted: Boolean
            val replaceBackend: Boolean
            val frameCommands: List<(Live2DModelBackend) -> Unit>

            synchronized(lock) {
                while (true) {
                    if (exitRequested) return
                    if (releaseSurfaceRequested) {
                        releaseWindowSurface()
                        releaseSurfaceRequested = false
                        surfaceReleasedAck = true
                        lock.notifyAll()
                        continue
                    }
                    val hasSurface = pendingTexture != null
                    if (hasSurface && (renderOnce || backendReplaceRequested)) break
                    if (hasSurface && targetFps > 0) {
                        val due = lastFrameNanos + FramePacingPolicy.frameIntervalNanos(targetFps)
                        val waitNanos = due - System.nanoTime()
                        if (lastFrameNanos == 0L || waitNanos <= 0L) break
                        lock.wait(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
                        continue
                    }
                    // Parked: no surface or 0 FPS -> no GL work at all until state changes.
                    reportFps(0)
                    lastFrameNanos = 0L
                    lock.wait()
                }
                texture = pendingTexture!!
                w = width
                h = height
                sizeChanged = sizeDirty
                sizeDirty = false
                renderOnce = false
                fps = targetFps
                replaceBackend = backendReplaceRequested
                backendReplaceRequested = false
                val now = System.nanoTime()
                maskWanted = !hitMaskSuppressed &&
                    (hitMaskRequested || now - lastMaskNanos >= MASK_REFRESH_NANOS)
                hitMaskRequested = false
                frameCommands = ArrayList(commands)
                commands.clear()
            }

            try {
                if (replaceBackend) swapBackend()
                sizeChanged = ensureGl(texture) || sizeChanged
                val active = backend ?: continue
                if (sizeChanged && w > 0 && h > 0) active.onSurfaceSizeChanged(w, h)

                val now = System.nanoTime()
                val rawDelta = if (lastFrameNanos == 0L) {
                    1f / fps.coerceAtLeast(FramePacingPolicy.FPS_PEEK)
                } else {
                    (now - lastFrameNanos) / 1_000_000_000f
                }
                lastFrameNanos = now
                for (command in frameCommands) command(active)
                active.onDrawFrame(FramePacingPolicy.clampDeltaSeconds(rawDelta))

                if (maskWanted && w > 0 && h > 0) {
                    callbacks.onHitMask(readHitMask(w, h))
                    lastMaskNanos = now
                }

                when (val result = egl.swapBuffers(windowSurface)) {
                    EGL14.EGL_SUCCESS -> countFrame(now)
                    EGL14.EGL_CONTEXT_LOST -> {
                        Log.w(TAG, "EGL context lost; rebuilding GL state")
                        teardownGl()
                    }
                    else -> {
                        Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(result)}")
                        releaseWindowSurface()
                        sleep(BACKOFF_MS)
                    }
                }
                val motion = active.isMotionActive
                if (motion != reportedMotion) {
                    reportedMotion = motion
                    callbacks.onMotionActiveChanged(motion)
                }
            } catch (e: InterruptedException) {
                return
            } catch (t: Throwable) {
                Log.e(TAG, "frame failed; resetting GL", t)
                teardownGl()
                sleep(BACKOFF_MS)
            }
        }
    }

    /** @return true if the surface was (re)created and the viewport must be refreshed. */
    private fun ensureGl(texture: SurfaceTexture): Boolean {
        var recreated = false
        if (!egl.isReady) {
            egl.initialize()
            backendGlReady = false
        }
        if (windowSurface == EGL14.EGL_NO_SURFACE || attachedTexture !== texture) {
            releaseWindowSurface()
            windowSurface = egl.createWindowSurface(texture)
            attachedTexture = texture
            recreated = true
        }
        egl.makeCurrent(windowSurface)
        if (backend == null) backend = backendProvider()
        if (!backendGlReady) {
            val current = backend!!
            try {
                current.onGlContextCreated()
            } catch (t: Throwable) {
                Log.w(TAG, "backend ${current.name} failed to init, falling back to placeholder", t)
                runCatching { current.release() }
                backend = PlaceholderNekoBackend().also { it.onGlContextCreated() }
            }
            backendGlReady = true
            recreated = true
            callbacks.onBackendReady(backend!!.name)
        }
        return recreated
    }

    private fun swapBackend() {
        if (egl.isReady && backendGlReady) runCatching { backend?.release() }
        backend = null
        backendGlReady = false
    }

    private fun readHitMask(w: Int, h: Int): HitTestMask {
        val needed = w * h * 4
        val buffer = readbackBuffer?.takeIf { it.capacity() >= needed }
            ?: ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder()).also { readbackBuffer = it }
        buffer.clear()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        return HitTestMask.fromGlReadPixels(buffer, w, h)
    }

    private fun countFrame(now: Long) {
        if (fpsWindowStart == 0L) fpsWindowStart = now
        framesInWindow++
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1_000_000_000L) {
            reportFps(Math.round(framesInWindow * 1_000_000_000.0 / elapsed).toInt())
            framesInWindow = 0
            fpsWindowStart = now
        }
    }

    private fun reportFps(fps: Int) {
        if (fps == 0) {
            framesInWindow = 0
            fpsWindowStart = 0L
        }
        if (fps != reportedFps) {
            reportedFps = fps
            callbacks.onFpsMeasured(fps)
        }
    }

    private fun releaseWindowSurface() {
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            egl.releaseWindowSurface(windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
        attachedTexture = null
    }

    private fun teardownGl() {
        if (egl.isReady) {
            if (backendGlReady) runCatching { backend?.release() }
            releaseWindowSurface()
            egl.release()
        }
        windowSurface = EGL14.EGL_NO_SURFACE
        attachedTexture = null
        backendGlReady = false
        lastFrameNanos = 0L
    }

    private companion object {
        const val TAG = "Live2DRenderThread"
        const val MASK_REFRESH_NANOS = 500_000_000L
        const val BACKOFF_MS = 250L
    }
}
