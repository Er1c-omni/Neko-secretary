package com.neko.secretary.overlay

import android.content.Context
import com.neko.secretary.live2d.CharacterStateMachine
import com.neko.secretary.protocol.AssistantMode
import com.neko.secretary.protocol.CharacterDirective
import com.neko.secretary.protocol.CharacterStateId
import com.neko.secretary.protocol.DirectiveAction
import com.neko.secretary.protocol.TodayScript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Manages the daily first-unlock "今日 3 秒剧本卡" (office / wfh / interview / holiday).
 */
object DailyScriptTracker {
    private const val FILE = "neko_daily_script"
    private const val KEY_DATE = "last_prompt_date"
    private const val KEY_SCRIPT = "today_script"
    private const val KEY_TTS_USER_ENABLED = "tts_user_enabled"

    private val _todayScript = MutableStateFlow<TodayScript?>(null)
    val todayScript: StateFlow<TodayScript?> = _todayScript.asStateFlow()

    private val _ttsUserEnabled = MutableStateFlow(false)
    val ttsUserEnabled: StateFlow<Boolean> = _ttsUserEnabled.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun restore(context: Context) {
        val p = prefs(context)
        val raw = p.getString(KEY_SCRIPT, null)
        _todayScript.value = raw?.let { runCatching { TodayScript.valueOf(it) }.getOrNull() }
        _ttsUserEnabled.value = p.getBoolean(KEY_TTS_USER_ENABLED, false)
    }

    fun setTtsUserEnabled(context: Context, enabled: Boolean) {
        _ttsUserEnabled.value = enabled
        prefs(context).edit().putBoolean(KEY_TTS_USER_ENABLED, enabled).apply()
    }

    fun shouldPromptToday(context: Context, todayKey: String = LocalDate.now().toString()): Boolean {
        return prefs(context).getString(KEY_DATE, null) != todayKey
    }

    fun markPromptedToday(context: Context, todayKey: String = LocalDate.now().toString()) {
        prefs(context).edit().putString(KEY_DATE, todayKey).apply()
    }

    fun buildFirstUnlockDirective(todayKey: String = LocalDate.now().toString()): CharacterDirective =
        CharacterDirective(
            directiveId = "daily_script_prompt:$todayKey",
            mode = CharacterStateMachine.mode.value,
            state = CharacterStateId.LIFE_GREETING,
            stealthOverride = CharacterStateMachine.stealthOffice.value,
            text = "早安！3 秒定调今天状态，选个剧本吧：",
            expression = "smile_gentle",
            motion = "TapBody",
            costume = if (CharacterStateMachine.mode.value == AssistantMode.SECRETARY) "secretary" else "default",
            ttsAllowed = false,
            ttsCacheKey = null,
            actions = listOf(
                DirectiveAction("script:office", "上班"),
                DirectiveAction("script:wfh", "居家"),
                DirectiveAction("script:interview", "面试"),
                DirectiveAction("script:holiday", "休假")
            )
        )

    fun applyScript(context: Context, script: TodayScript, todayKey: String = LocalDate.now().toString()) {
        _todayScript.value = script
        prefs(context).edit()
            .putString(KEY_DATE, todayKey)
            .putString(KEY_SCRIPT, script.name)
            .apply()

        when (script) {
            TodayScript.OFFICE -> {
                setTtsUserEnabled(context, false)
                CharacterStateMachine.onModeChanged(AssistantMode.LIFE, stealthOffice = true)
                StealthController.set(context, true)
            }
            TodayScript.WFH -> {
                setTtsUserEnabled(context, true)
                StealthController.set(context, false)
                CharacterStateMachine.onModeChanged(AssistantMode.LIFE, stealthOffice = false)
                CharacterStateMachine.applyDirective(
                    CharacterDirective(
                        directiveId = "script_applied:$todayKey:wfh",
                        mode = AssistantMode.LIFE,
                        state = CharacterStateId.LIFE_GREETING,
                        stealthOverride = false,
                        text = "居家办公模式已开启~ 温和语音提醒已就绪！",
                        expression = "smile_gentle",
                        motion = "TapBody",
                        costume = "default",
                        ttsAllowed = true,
                        ttsCacheKey = "morning_wfh_01"
                    )
                )
            }
            TodayScript.INTERVIEW -> {
                setTtsUserEnabled(context, false)
                CharacterStateMachine.onModeChanged(AssistantMode.SECRETARY, stealthOffice = true)
                StealthController.set(context, true)
            }
            TodayScript.HOLIDAY -> {
                setTtsUserEnabled(context, true)
                StealthController.set(context, false)
                CharacterStateMachine.onModeChanged(AssistantMode.LIFE, stealthOffice = false)
                CharacterStateMachine.applyDirective(
                    CharacterDirective(
                        directiveId = "script_applied:$todayKey:holiday",
                        mode = AssistantMode.LIFE,
                        state = CharacterStateId.LIFE_GREETING,
                        stealthOverride = false,
                        text = "放假啦~ 今天减少工作打扰，好好休息喵！",
                        expression = "smile_gentle",
                        motion = "TapBody",
                        costume = "default",
                        ttsAllowed = true,
                        ttsCacheKey = "morning_holiday_01"
                    )
                )
            }
        }
    }
}
