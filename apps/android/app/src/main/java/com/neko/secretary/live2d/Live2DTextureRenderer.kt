package com.neko.secretary.live2d

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TextureView facade over [Live2DRenderThread].
 *
 * - Transparent compositing: `TextureView.isOpaque = false` + RGBA8888 EGL config + premultiplied output.
 * - Adaptive FPS: the overlay service feeds [setTargetFps] from [FramePacingPolicy] (60/20/10/0).
 * - Click-through: [hitTest] answers from the latest alpha [HitTestMask] so touches on transparent
 *   canvas can be released to the app underneath.
 * - Backend: Cubism when the SDK + a model pack are present, otherwise [PlaceholderNekoBackend].
 */
class Live2DTextureRenderer(
    context: Context,
    private val modelSourceProvider: () -> Live2DModelSource?
) : TextureView.SurfaceTextureListener {
    private val appContext = context.applicationContext

    private var thread: Live2DRenderThread? = null

    @Volatile
    private var targetFps: Int = FramePacingPolicy.FPS_PEEK

    @Volatile
    private var hitMask: HitTestMask? = null

    @Volatile
    private var lastLook: CharacterLook? = null

    private val _measuredFps = MutableStateFlow(0)
    val measuredFps: StateFlow<Int> = _measuredFps.asStateFlow()

    private val _motionActive = MutableStateFlow(false)
    val motionActive: StateFlow<Boolean> = _motionActive.asStateFlow()

    private val _backendName = MutableStateFlow("none")
    val backendName: StateFlow<String> = _backendName.asStateFlow()

    private val callbacks = object : Live2DRenderThread.Callbacks {
        override fun onHitMask(mask: HitTestMask) {
            hitMask = mask
        }

        override fun onFpsMeasured(fps: Int) {
            _measuredFps.value = fps
        }

        override fun onMotionActiveChanged(active: Boolean) {
            _motionActive.value = active
        }

        override fun onBackendReady(name: String) {
            _backendName.value = name
        }
    }

    fun attach(view: TextureView) {
        view.isOpaque = false
        view.surfaceTextureListener = this
        val existing = view.surfaceTexture
        if (view.isAvailable && existing != null) {
            onSurfaceTextureAvailable(existing, view.width, view.height)
        }
    }

    fun setTargetFps(fps: Int) {
        targetFps = fps.coerceIn(0, FramePacingPolicy.FPS_INTERACTIVE)
        thread?.setTargetFps(targetFps)
    }

    fun currentTargetFps(): Int = targetFps

    fun applyLook(look: CharacterLook) {
        lastLook = look
        thread?.enqueue { it.applyLook(look) }
    }

    fun onTap(xNorm: Float, yNorm: Float) {
        thread?.enqueue { it.onTap(xNorm, yNorm) }
    }

    fun setMouthOpen(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        thread?.enqueue { it.setMouthOpen(clamped) }
    }

    /** True when (x, y) in TextureView pixels hits the character (with finger tolerance). */
    fun hitTest(x: Float, y: Float): Boolean = hitMask?.isNearOpaque(x, y) ?: true

    fun opaqueBounds(): PixelRect? = hitMask?.opaqueBounds

    fun setInteracting(interacting: Boolean) {
        thread?.setHitMaskSuppressed(interacting)
        if (!interacting) thread?.requestHitMask()
    }

    /** Re-resolve the model source (e.g. after importing a new .zip pack) on the GL thread. */
    fun reloadModel() {
        thread?.replaceBackend()
        lastLook?.let { look -> thread?.enqueue { it.applyLook(look) } }
    }

    fun release() {
        thread?.requestExitAndWait()
        thread = null
        hitMask = null
        _measuredFps.value = 0
    }

    private fun ensureThread(): Live2DRenderThread {
        thread?.takeIf { it.isAlive }?.let { return it }
        val created = Live2DRenderThread(
            backendProvider = { Live2DBackendFactory.create(appContext, modelSourceProvider()) },
            callbacks = callbacks
        )
        created.setTargetFps(targetFps)
        lastLook?.let { look -> created.enqueue { it.applyLook(look) } }
        created.start()
        thread = created
        return created
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        hitMask = HitTestMask.fullyOpaque(width, height)
        ensureThread().onSurfaceAvailable(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        thread?.onSurfaceSizeChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        thread?.onSurfaceDestroyed()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
