package com.neko.secretary.live2d

import com.neko.secretary.protocol.AssistantMode
import com.neko.secretary.protocol.CharacterDirective
import com.neko.secretary.protocol.CharacterStateId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CharacterStateMachine {
    private val _currentState = MutableStateFlow(CharacterStateId.PEEK_IDLE)
    val currentState: StateFlow<CharacterStateId> = _currentState.asStateFlow()

    private val _lastDirective = MutableStateFlow<CharacterDirective?>(null)
    val lastDirective: StateFlow<CharacterDirective?> = _lastDirective.asStateFlow()

    fun applyDirective(directive: CharacterDirective) {
        _lastDirective.value = directive
        _currentState.value = if (directive.stealthOverride) {
            CharacterStateId.STEALTH_OFFICE
        } else {
            directive.state
        }
    }

    fun onModeChanged(mode: AssistantMode, stealthOffice: Boolean) {
        _currentState.value = when {
            stealthOffice -> CharacterStateId.STEALTH_OFFICE
            mode == AssistantMode.SECRETARY -> CharacterStateId.SECRETARY_ACTIVE
            else -> CharacterStateId.PEEK_IDLE
        }
    }

    fun onStealthChanged(stealthOffice: Boolean, mode: AssistantMode) {
        onModeChanged(mode, stealthOffice)
    }

    fun targetFpsFor(state: CharacterStateId): Int = when (state) {
        CharacterStateId.STEALTH_OFFICE -> 0
        CharacterStateId.PEEK_IDLE -> 10
        CharacterStateId.DOCK_STANDBY -> 20
        else -> 60
    }
}
