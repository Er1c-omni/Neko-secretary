package com.neko.secretary.overlay

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService
import com.neko.secretary.live2d.CharacterStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live overlay telemetry for the console (verifies "lock screen / stealth => GPU 0 FPS"). */
object OverlayRuntime {
    internal val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    internal val _targetFps = MutableStateFlow(0)
    val targetFps: StateFlow<Int> = _targetFps.asStateFlow()

    internal val _measuredFps = MutableStateFlow(0)
    val measuredFps: StateFlow<Int> = _measuredFps.asStateFlow()

    internal val _backend = MutableStateFlow("none")
    val backend: StateFlow<String> = _backend.asStateFlow()

    internal val _docked = MutableStateFlow(true)
    val docked: StateFlow<Boolean> = _docked.asStateFlow()

    internal val _passThrough = MutableStateFlow(false)
    val passThrough: StateFlow<Boolean> = _passThrough.asStateFlow()

    internal val _voiceActive = MutableStateFlow(false)
    val voiceActive: StateFlow<Boolean> = _voiceActive.asStateFlow()

    internal val _voiceCancelArmed = MutableStateFlow(false)
    val voiceCancelArmed: StateFlow<Boolean> = _voiceCancelArmed.asStateFlow()

    internal val _voiceProvider = MutableStateFlow("idle")
    val voiceProvider: StateFlow<String> = _voiceProvider.asStateFlow()

    internal val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    /** Optional sink invoked with standard `user.voice_transcript` EventEnvelope JSON. */
    @Volatile
    var onVoiceTranscriptEvent: ((String) -> Unit)? = null
}

/** Non-secret UI placement prefs (no SSIDs / keys here, per ENGINEERING_SPEC §1.3). */
internal object OverlayPrefs {
    private const val FILE = "neko_overlay"
    private const val KEY_SIDE = "dock_side"
    private const val KEY_Y_RATIO = "dock_y_ratio"
    private const val KEY_STEALTH = "stealth_office"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun side(context: Context): DockSide =
        runCatching { DockSide.valueOf(prefs(context).getString(KEY_SIDE, DockSide.RIGHT.name)!!) }
            .getOrDefault(DockSide.RIGHT)

    fun yRatio(context: Context): Float = prefs(context).getFloat(KEY_Y_RATIO, 0.38f)

    fun saveDock(context: Context, side: DockSide, yRatio: Float) {
        prefs(context).edit().putString(KEY_SIDE, side.name).putFloat(KEY_Y_RATIO, yRatio).apply()
    }

    fun stealth(context: Context): Boolean = prefs(context).getBoolean(KEY_STEALTH, false)

    fun saveStealth(context: Context, stealth: Boolean) {
        prefs(context).edit().putBoolean(KEY_STEALTH, stealth).apply()
    }
}

/**
 * One-tap "职场防社死" stealth switch shared by the notification action, Quick Settings tile and
 * console. Stealth only changes presentation (0 FPS badge, muted TTS); SafetyGate is untouched.
 */
object StealthController {
    fun restore(context: Context) {
        CharacterStateMachine.onStealthChanged(OverlayPrefs.stealth(context))
    }

    fun set(context: Context, stealth: Boolean) {
        CharacterStateMachine.onStealthChanged(stealth)
        OverlayPrefs.saveStealth(context, stealth)
        refreshTile(context)
    }

    fun toggle(context: Context): Boolean {
        val next = !CharacterStateMachine.stealthOffice.value
        set(context, next)
        return next
    }

    private fun refreshTile(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                TileService.requestListeningState(context, ComponentName(context, StealthTileService::class.java))
            }
        }
    }
}
