package com.neko.secretary.voice

import kotlin.math.sqrt

enum class VoiceGesturePhase {
    IDLE,
    RECORDING,
    CANCEL_ARMED,
    FINALIZING
}

enum class VoiceGestureOutcome {
    NONE,
    SEND,
    CANCELLED,
    TOO_SHORT
}

/**
 * Pure state machine for "long-press pet to speak, release to send, slide up to cancel".
 * Includes hysteresis around the cancel threshold so fingers near the boundary don't flicker.
 */
class VoiceGestureMachine(
    private val cancelThresholdPx: Float = 160f,
    private val hysteresisPx: Float = 24f,
    private val minDurationMs: Long = 300L,
    private val maxDurationMs: Long = 60_000L
) {
    var phase: VoiceGesturePhase = VoiceGesturePhase.IDLE
        private set

    private var anchorRawY: Float = 0f
    private var startedAtMs: Long = 0L

    val isActive: Boolean
        get() = phase == VoiceGesturePhase.RECORDING || phase == VoiceGesturePhase.CANCEL_ARMED

    fun start(anchorRawY: Float, nowMs: Long): Boolean {
        if (phase != VoiceGesturePhase.IDLE) return false
        this.anchorRawY = anchorRawY
        this.startedAtMs = nowMs
        phase = VoiceGesturePhase.RECORDING
        return true
    }

    /**
     * Update pointer Y during hold.
     * @return true if the phase changed (RECORDING <-> CANCEL_ARMED).
     */
    fun onMove(currentRawY: Float): Boolean {
        val upwardDrag = anchorRawY - currentRawY
        val next = when (phase) {
            VoiceGesturePhase.RECORDING ->
                if (upwardDrag >= cancelThresholdPx) VoiceGesturePhase.CANCEL_ARMED else phase
            VoiceGesturePhase.CANCEL_ARMED ->
                if (upwardDrag < (cancelThresholdPx - hysteresisPx).coerceAtLeast(0f)) VoiceGesturePhase.RECORDING else phase
            else -> phase
        }
        if (next == phase) return false
        phase = next
        return true
    }

    fun isExpired(nowMs: Long): Boolean = isActive && (nowMs - startedAtMs) >= maxDurationMs

    fun elapsedMs(nowMs: Long): Long = if (startedAtMs > 0L) (nowMs - startedAtMs).coerceAtLeast(0L) else 0L

    fun release(nowMs: Long): VoiceGestureOutcome {
        val outcome = when (phase) {
            VoiceGesturePhase.CANCEL_ARMED -> {
                phase = VoiceGesturePhase.IDLE
                VoiceGestureOutcome.CANCELLED
            }
            VoiceGesturePhase.RECORDING -> {
                if (nowMs - startedAtMs < minDurationMs) {
                    phase = VoiceGesturePhase.IDLE
                    VoiceGestureOutcome.TOO_SHORT
                } else {
                    phase = VoiceGesturePhase.FINALIZING
                    VoiceGestureOutcome.SEND
                }
            }
            else -> VoiceGestureOutcome.NONE
        }
        return outcome
    }

    fun abort(): VoiceGestureOutcome {
        if (phase == VoiceGesturePhase.IDLE) return VoiceGestureOutcome.NONE
        phase = VoiceGesturePhase.IDLE
        return VoiceGestureOutcome.CANCELLED
    }

    fun markFinished() {
        phase = VoiceGesturePhase.IDLE
    }
}

/**
 * Strips common Chinese/English speech filler words ("口头禅") before dispatching to Intent Router,
 * while preserving meaningful content (docs/ARCHITECTURE.md §6.3).
 */
object TranscriptCleaner {
    private val LEADING_FILLERS = Regex(
        """^(?:[嗯呃啊哦唉诶额]+|那个|就是说?|然后呢?|其实吧?|\b(?:um|uh|er|like|you know)\b)[，,、。\s]*""",
        RegexOption.IGNORE_CASE
    )
    private val INLINE_FILLERS = Regex(
        """(?<=[\s，,、。])(?:[嗯呃啊诶额]+|就是说|那个)(?=[\s，,、。])"""
    )
    private val MULTI_SPACE = Regex("""\s+""")

    fun clean(raw: String): String {
        var text = raw.trim()
        // Peel repeated leading fillers (e.g. "嗯，那个，提醒我喝水")
        var previous: String
        do {
            previous = text
            text = LEADING_FILLERS.replaceFirst(text, "").trimStart()
        } while (text != previous && text.isNotEmpty())

        text = INLINE_FILLERS.replace(text, "")
        text = MULTI_SPACE.replace(text, " ").trim()
        return text
    }
}

/**
 * Computes normalized RMS amplitude (0f..1f) from little-endian 16-bit signed PCM audio.
 * Drives live mic meter and prepares the lip-sync envelope for Week 2.
 */
object PcmAudioMath {
    fun normalizedRms(pcm16Le: ByteArray, length: Int = pcm16Le.size): Float {
        val samples = length.coerceAtMost(pcm16Le.size) / 2
        if (samples <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until samples) {
            val lo = pcm16Le[i * 2].toInt() and 0xFF
            val hi = pcm16Le[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            val norm = sample / 32768.0
            sumSq += norm * norm
        }
        return sqrt(sumSq / samples).toFloat().coerceIn(0f, 1f)
    }
}
