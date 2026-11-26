package com.neko.secretary.live2d

import com.neko.secretary.protocol.AssistantMode
import com.neko.secretary.protocol.CharacterDirective
import com.neko.secretary.protocol.CharacterStateId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the companion's 10-state machine (docs/ARCHITECTURE.md §6.2).
 *
 * Stealth is an orthogonal flag: while [stealthOffice] is true the visible state is always
 * STEALTH_OFFICE (0 FPS badge, TTS muted) regardless of incoming directives, and the underlying
 * "resting" state is restored when stealth is turned off. Persona mode never touches SafetyGate.
 */
object CharacterStateMachine {
    private val _currentState = MutableStateFlow(CharacterStateId.PEEK_IDLE)
    val currentState: StateFlow<CharacterStateId> = _currentState.asStateFlow()

    private val _lastDirective = MutableStateFlow<CharacterDirective?>(null)
    val lastDirective: StateFlow<CharacterDirective?> = _lastDirective.asStateFlow()

    private val _mode = MutableStateFlow(AssistantMode.LIFE)
    val mode: StateFlow<AssistantMode> = _mode.asStateFlow()

    private val _stealthOffice = MutableStateFlow(false)
    val stealthOffice: StateFlow<Boolean> = _stealthOffice.asStateFlow()

    /** State to fall back to after a transient directive or when leaving stealth. */
    private fun restingState(): CharacterStateId =
        if (_mode.value == AssistantMode.SECRETARY) CharacterStateId.SECRETARY_ACTIVE else CharacterStateId.PEEK_IDLE

    @Synchronized
    fun applyDirective(directive: CharacterDirective) {
        _lastDirective.value = directive
        if (directive.stealthOverride) _stealthOffice.value = true
        _currentState.value = if (_stealthOffice.value) CharacterStateId.STEALTH_OFFICE else directive.state
    }

    /** Return to the resting state after a directive bubble is dismissed / times out. */
    @Synchronized
    fun settle() {
        _currentState.value = if (_stealthOffice.value) CharacterStateId.STEALTH_OFFICE else restingState()
    }

    @Synchronized
    fun onModeChanged(mode: AssistantMode, stealthOffice: Boolean = _stealthOffice.value) {
        _mode.value = mode
        _stealthOffice.value = stealthOffice
        settle()
    }

    @Synchronized
    fun onStealthChanged(stealthOffice: Boolean, mode: AssistantMode = _mode.value) {
        onModeChanged(mode, stealthOffice)
    }

    @Synchronized
    fun toggleStealth(): Boolean {
        onStealthChanged(!_stealthOffice.value)
        return _stealthOffice.value
    }

    /** Coarse baseline used when no interaction context is known (see [FramePacingPolicy]). */
    fun targetFpsFor(state: CharacterStateId): Int = FramePacingPolicy.targetFps(
        RenderConditions(
            state = state,
            screenInteractive = true,
            overlayVisible = true,
            interacting = false,
            motionActive = state != CharacterStateId.PEEK_IDLE &&
                state != CharacterStateId.DOCK_STANDBY &&
                state != CharacterStateId.STEALTH_OFFICE &&
                state != CharacterStateId.SECRETARY_ACTIVE,
            docked = state == CharacterStateId.PEEK_IDLE
        )
    )
}
