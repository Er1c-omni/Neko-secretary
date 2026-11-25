package com.neko.secretary.live2d

import android.graphics.SurfaceTexture
import android.view.TextureView

/**
 * TextureView + custom EGLThread host for transparent overlay Live2D rendering.
 * Enforces adaptive FPS (60/20/10/0) based on CharacterStateMachine to protect battery.
 */
class Live2DTextureRenderer : TextureView.SurfaceTextureListener {
    @Volatile
    private var targetFps: Int = 10

    @Volatile
    private var running: Boolean = false

    fun setTargetFps(fps: Int) {
        targetFps = fps.coerceIn(0, 60)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        running = true
        // Week 1 Day 2-3: Initialize EGLContext on dedicated GL thread and bind CubismRenderer.
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // Resize viewport for Cubism projection matrix.
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        running = false
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
