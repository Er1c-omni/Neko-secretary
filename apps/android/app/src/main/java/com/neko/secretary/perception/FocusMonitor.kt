package com.neko.secretary.perception

import android.app.usage.UsageStatsManager
import android.content.Context
import com.neko.secretary.live2d.CharacterStateMachine
import com.neko.secretary.protocol.AssistantMode
import com.neko.secretary.protocol.CharacterDirective
import com.neko.secretary.protocol.CharacterStateId
import com.neko.secretary.sync.EventSyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

data class DistractionAlert(
    val packageName: String,
    val appLabel: String,
    val continuousMinutes: Int
)

/**
 * Pure distraction detector for Focus Mode (JVM-testable).
 * Tracks continuous foreground time on entertainment apps and fires once threshold is reached
 * (with cooldown so it never spams).
 */
class DistractionDetector(
    private val distractionPackages: Map<String, String> = DEFAULT_DISTRACTION_APPS,
    private val thresholdMs: Long = 5L * 60_000L,
    private val cooldownMs: Long = 10L * 60_000L
) {
    private var currentPkg: String? = null
    private var streakStartMs: Long = 0L
    private var lastAlertAtMs: Long = 0L

    fun onSample(foregroundPackage: String?, nowMs: Long): DistractionAlert? {
        if (foregroundPackage == null || !distractionPackages.containsKey(foregroundPackage)) {
            currentPkg = null
            streakStartMs = 0L
            return null
        }
        if (foregroundPackage != currentPkg) {
            currentPkg = foregroundPackage
            streakStartMs = nowMs
            return null
        }
        val elapsed = nowMs - streakStartMs
        if (elapsed >= thresholdMs && (lastAlertAtMs == 0L || nowMs - lastAlertAtMs >= cooldownMs)) {
            lastAlertAtMs = nowMs
            val mins = (elapsed / 60_000L).toInt().coerceAtLeast(1)
            return DistractionAlert(
                packageName = foregroundPackage,
                appLabel = distractionPackages[foregroundPackage] ?: foregroundPackage,
                continuousMinutes = mins
            )
        }
        return null
    }

    fun reset() {
        currentPkg = null
        streakStartMs = 0L
        lastAlertAtMs = 0L
    }

    companion object {
        val DEFAULT_DISTRACTION_APPS: Map<String, String> = mapOf(
            "tv.danmaku.bili" to "B 站",
            "com.ss.android.ugc.aweme" to "抖音",
            "com.xingin.xhs" to "小红书",
            "com.sina.weibo" to "微博",
            "com.google.android.youtube" to "YouTube",
            "com.zhiliaoapp.musically" to "TikTok"
        )
    }
}

/**
 * Week 2 Day 12: Public `UsageStatsManager` focus monitor (P4 public Android API).
 * When Focus Mode is enabled, polls recent foreground app every 20s and triggers
 * `ANTI_PROCRASTINATE` companion intervention once entertainment usage exceeds 5 minutes.
 */
object FocusUsageMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val detector = DistractionDetector()
    private var monitorJob: Job? = null

    private val _focusActive = MutableStateFlow(false)
    val focusActive: StateFlow<Boolean> = _focusActive.asStateFlow()

    fun setFocusMode(context: Context, enabled: Boolean) {
        _focusActive.value = enabled
        monitorJob?.cancel()
        monitorJob = null
        detector.reset()
        if (!enabled) return

        val appContext = context.applicationContext
        monitorJob = scope.launch {
            while (isActive) {
                val fg = queryCurrentForegroundPackage(appContext)
                val alert = detector.onSample(fg, System.currentTimeMillis())
                if (alert != null) {
                    triggerAntiProcrastinate(appContext, alert)
                }
                delay(20_000L)
            }
        }
    }

    private fun queryCurrentForegroundPackage(context: Context): String? = runCatching {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 60_000L, end)
        stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }.getOrNull()

    private fun triggerAntiProcrastinate(context: Context, alert: DistractionAlert) {
        val stealth = CharacterStateMachine.stealthOffice.value
        val mode = CharacterStateMachine.mode.value
        CharacterStateMachine.applyDirective(
            CharacterDirective(
                directiveId = "focus_catch:${alert.packageName}:${System.currentTimeMillis() / 60_000L}",
                mode = mode,
                state = if (stealth) CharacterStateId.STEALTH_OFFICE else CharacterStateId.ANTI_PROCRASTINATE,
                stealthOverride = stealth,
                text = "抓包！专注期内刷 ${alert.appLabel} 已经 ${alert.continuousMinutes} 分钟啦，快回正事哦~",
                expression = "pout",
                motion = "TapBody",
                costume = if (mode == AssistantMode.SECRETARY) "secretary" else "default",
                ttsAllowed = !stealth,
                ttsCacheKey = "focus_catch_01"
            )
        )
        val eventId = UUID.randomUUID().toString()
        val envelope = JSONObject()
            .put("id", eventId)
            .put("idempotencyKey", "android:focus:$eventId")
            .put("type", "sensor.usage_distraction")
            .put("source", "android")
            .put("ts", System.currentTimeMillis())
            .put(
                "payload",
                JSONObject()
                    .put("packageName", alert.packageName)
                    .put("appLabel", alert.appLabel)
                    .put("minutes", alert.continuousMinutes)
            )
            .toString()
        EventSyncCoordinator.sendEventAsync(context, envelope)
    }
}
