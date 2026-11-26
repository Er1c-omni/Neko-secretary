package com.neko.secretary.live2d

import com.neko.secretary.protocol.AssistantMode
import com.neko.secretary.protocol.CharacterDirective
import com.neko.secretary.protocol.CharacterStateId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CharacterStateMachineTest {

    @Before
    fun reset() {
        CharacterStateMachine.onModeChanged(AssistantMode.LIFE, stealthOffice = false)
    }

    @Test
    fun `stealth mode overrides directives and restores resting state when disabled`() {
        CharacterStateMachine.onStealthChanged(true)
        assertTrue(CharacterStateMachine.stealthOffice.value)
        assertEquals(CharacterStateId.STEALTH_OFFICE, CharacterStateMachine.currentState.value)

        CharacterStateMachine.applyDirective(
            CharacterDirective(
                directiveId = "d-1",
                mode = AssistantMode.LIFE,
                state = CharacterStateId.LIFE_WATER,
                stealthOverride = false,
                text = "喝水啦",
                expression = "happy",
                motion = "Water",
                costume = "default",
                ttsAllowed = true,
                ttsCacheKey = "water_1"
            )
        )
        // Still locked in STEALTH_OFFICE while stealth is enabled
        assertEquals(CharacterStateId.STEALTH_OFFICE, CharacterStateMachine.currentState.value)

        CharacterStateMachine.onStealthChanged(false)
        assertFalse(CharacterStateMachine.stealthOffice.value)
        assertEquals(CharacterStateId.PEEK_IDLE, CharacterStateMachine.currentState.value)
    }

    @Test
    fun `directive with stealthOverride forces stealth on`() {
        CharacterStateMachine.applyDirective(
            CharacterDirective(
                directiveId = "d-2",
                mode = AssistantMode.SECRETARY,
                state = CharacterStateId.SECRETARY_ALERT,
                stealthOverride = true,
                text = "静默提醒",
                expression = "",
                motion = "",
                costume = "secretary",
                ttsAllowed = false,
                ttsCacheKey = null
            )
        )
        assertTrue(CharacterStateMachine.stealthOffice.value)
        assertEquals(CharacterStateId.STEALTH_OFFICE, CharacterStateMachine.currentState.value)
    }

    @Test
    fun `settle returns to mode-appropriate resting state`() {
        CharacterStateMachine.onModeChanged(AssistantMode.SECRETARY, stealthOffice = false)
        assertEquals(CharacterStateId.SECRETARY_ACTIVE, CharacterStateMachine.currentState.value)

        CharacterStateMachine.applyDirective(
            CharacterDirective(
                directiveId = "d-3",
                mode = AssistantMode.SECRETARY,
                state = CharacterStateId.SECRETARY_ALERT,
                stealthOverride = false,
                text = "Alert",
                expression = "",
                motion = "",
                costume = "secretary",
                ttsAllowed = false,
                ttsCacheKey = null
            )
        )
        assertEquals(CharacterStateId.SECRETARY_ALERT, CharacterStateMachine.currentState.value)

        CharacterStateMachine.settle()
        assertEquals(CharacterStateId.SECRETARY_ACTIVE, CharacterStateMachine.currentState.value)
    }

    @Test
    fun `targetFpsFor follows 60-20-10-0 tiers`() {
        assertEquals(0, CharacterStateMachine.targetFpsFor(CharacterStateId.STEALTH_OFFICE))
        assertEquals(10, CharacterStateMachine.targetFpsFor(CharacterStateId.PEEK_IDLE))
        assertEquals(20, CharacterStateMachine.targetFpsFor(CharacterStateId.DOCK_STANDBY))
        assertEquals(20, CharacterStateMachine.targetFpsFor(CharacterStateId.SECRETARY_ACTIVE))
        assertEquals(60, CharacterStateMachine.targetFpsFor(CharacterStateId.LIFE_WATER))
    }
}
