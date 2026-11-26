package com.neko.secretary.live2d

import com.neko.secretary.protocol.CharacterStateId

/**
 * Snapshot of everything that influences how hard the GPU should work for the companion.
 * Pure data so the policy is unit-testable on the JVM without Android.
 */
data class RenderConditions(
    val state: CharacterStateId,
    /** PowerManager.isInteractive && not sitting on the keyguard. */
    val screenInteractive: Boolean,
    /** Overlay window attached and the Live2D canvas is visible (not collapsed into the stealth badge). */
    val overlayVisible: Boolean,
    /** Finger down / drag / edge-snap animation in progress. */
    val interacting: Boolean,
    /** A directive motion, tap reaction or (later) lip-sync is playing. */
    val motionActive: Boolean,
    /** Companion is tucked into the screen edge (PEEK). */
    val docked: Boolean
)

/**
 * 60 / 20 / 10 / 0 adaptive frame pacing (docs/ARCHITECTURE.md §6.1):
 * - 60 FPS: interaction, motion playback, lip-sync
 * - 20 FPS: expanded standby / DOCK_STANDBY desk mode
 * - 10 FPS: edge peek (PEEK_IDLE)
 * - 0 FPS: screen off, keyguard, STEALTH_OFFICE, overlay hidden -> GL loop fully parked
 */
object FramePacingPolicy {
    const val FPS_INTERACTIVE = 60
    const val FPS_STANDBY = 20
    const val FPS_PEEK = 10
    const val FPS_PAUSED = 0

    fun targetFps(c: RenderConditions): Int = when {
        !c.screenInteractive || !c.overlayVisible -> FPS_PAUSED
        c.state == CharacterStateId.STEALTH_OFFICE -> FPS_PAUSED
        c.interacting || c.motionActive -> FPS_INTERACTIVE
        c.state == CharacterStateId.DOCK_STANDBY -> FPS_STANDBY
        c.docked -> FPS_PEEK
        else -> FPS_STANDBY
    }

    /** Nanoseconds between frames for a given fps; Long.MAX_VALUE means "parked". */
    fun frameIntervalNanos(fps: Int): Long =
        if (fps <= 0) Long.MAX_VALUE else 1_000_000_000L / fps.coerceAtMost(FPS_INTERACTIVE)

    /** Clamp the animation delta so resuming from a long pause never makes motions jump. */
    fun clampDeltaSeconds(rawSeconds: Float): Float = rawSeconds.coerceIn(0f, MAX_DELTA_SECONDS)

    const val MAX_DELTA_SECONDS = 0.1f
}
