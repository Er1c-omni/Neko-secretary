package com.neko.secretary.live2d

import com.neko.secretary.protocol.CharacterStateId
import org.junit.Assert.assertEquals
import org.junit.Test

class FramePacingPolicyTest {

    @Test
    fun `screen off or overlay hidden always parks GPU at 0 FPS`() {
        val screenOff = RenderConditions(
            state = CharacterStateId.LIFE_WATER,
            screenInteractive = false,
            overlayVisible = true,
            interacting = true,
            motionActive = true,
            docked = false
        )
        assertEquals(0, FramePacingPolicy.targetFps(screenOff))

        val hidden = screenOff.copy(screenInteractive = true, overlayVisible = false)
        assertEquals(0, FramePacingPolicy.targetFps(hidden))
    }

    @Test
    fun `stealth office always parks GPU at 0 FPS even when interacting`() {
        val stealth = RenderConditions(
            state = CharacterStateId.STEALTH_OFFICE,
            screenInteractive = true,
            overlayVisible = true,
            interacting = true,
            motionActive = true,
            docked = false
        )
        assertEquals(0, FramePacingPolicy.targetFps(stealth))
        assertEquals(Long.MAX_VALUE, FramePacingPolicy.frameIntervalNanos(0))
    }

    @Test
    fun `interaction or motion boosts to 60 FPS`() {
        val dragging = RenderConditions(
            state = CharacterStateId.PEEK_IDLE,
            screenInteractive = true,
            overlayVisible = true,
            interacting = true,
            motionActive = false,
            docked = true
        )
        assertEquals(60, FramePacingPolicy.targetFps(dragging))

        val motion = dragging.copy(interacting = false, motionActive = true)
        assertEquals(60, FramePacingPolicy.targetFps(motion))
    }

    @Test
    fun `expanded standby and dock standby run at 20 FPS`() {
        val expanded = RenderConditions(
            state = CharacterStateId.SECRETARY_ACTIVE,
            screenInteractive = true,
            overlayVisible = true,
            interacting = false,
            motionActive = false,
            docked = false
        )
        assertEquals(20, FramePacingPolicy.targetFps(expanded))

        val dockStandby = expanded.copy(state = CharacterStateId.DOCK_STANDBY, docked = true)
        assertEquals(20, FramePacingPolicy.targetFps(dockStandby))
    }

    @Test
    fun `docked peek idle throttles to 10 FPS`() {
        val peek = RenderConditions(
            state = CharacterStateId.PEEK_IDLE,
            screenInteractive = true,
            overlayVisible = true,
            interacting = false,
            motionActive = false,
            docked = true
        )
        assertEquals(10, FramePacingPolicy.targetFps(peek))
        assertEquals(100_000_000L, FramePacingPolicy.frameIntervalNanos(10))
    }

    @Test
    fun `delta seconds clamped to prevent animation jumps after pause`() {
        assertEquals(0f, FramePacingPolicy.clampDeltaSeconds(-1f), 0.0001f)
        assertEquals(0.016f, FramePacingPolicy.clampDeltaSeconds(0.016f), 0.0001f)
        assertEquals(FramePacingPolicy.MAX_DELTA_SECONDS, FramePacingPolicy.clampDeltaSeconds(5.0f), 0.0001f)
    }
}
