package com.neko.secretary.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.neko.secretary.live2d.CharacterStateMachine
import com.neko.secretary.protocol.AssistantMode
import com.neko.secretary.protocol.CharacterDirective
import com.neko.secretary.protocol.CharacterStateId
import com.neko.secretary.protocol.DirectiveAction

/**
 * Local `AlarmManager` offline fallback scheduler (Week 1 Day 6–7).
 *
 * Guarantees conflict-free coordination with Core Hub:
 * - If Core Hub WS is currently connected (`EventSyncCoordinator.isConnected`), the local alarm
 *   skips emitting a duplicate bubble and lets Core Hub drive the reminder.
 * - Even if WS just dropped, `FireKeyDeduplicator` blocks any slot already delivered within the
 *   dedup window so the user never sees double pop-ups.
 * - Reschedules itself automatically after device reboot (`BOOT_COMPLETED`).
 */
object OfflineReminderScheduler {
    private const val PREFS_FILE = "neko_offline_reminders"
    private const val KEY_WATER_ENABLED = "water_enabled"
    private const val KEY_WATER_INTERVAL_MIN = "water_interval_min"
    private const val REQUEST_CODE_WATER = 4101
    const val ACTION_WATER_ALARM = "com.neko.secretary.sync.ACTION_WATER_ALARM"
    const val JOB_ID_WATER = "job_water_hourly"

    fun scheduleWaterFallback(context: Context, intervalMinutes: Int = 60) {
        val mins = intervalMinutes.coerceAtLeast(1)
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WATER_ENABLED, true)
            .putInt(KEY_WATER_INTERVAL_MIN, mins)
            .apply()
        armNextAlarm(context, mins)
    }

    fun cancelWaterFallback(context: Context) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WATER_ENABLED, false)
            .apply()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(waterPendingIntent(context))
    }

    fun restoreAfterBoot(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_WATER_ENABLED, false)) {
            val mins = prefs.getInt(KEY_WATER_INTERVAL_MIN, 60)
            armNextAlarm(context, mins)
        }
    }

    internal fun armNextAlarm(context: Context, intervalMinutes: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAt = System.currentTimeMillis() + intervalMinutes * 60_000L
        val pi = waterPendingIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun waterPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, OfflineReminderReceiver::class.java).apply {
            action = ACTION_WATER_ALARM
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_WATER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class OfflineReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                OfflineReminderScheduler.restoreAfterBoot(context)
            }
            OfflineReminderScheduler.ACTION_WATER_ALARM -> {
                handleWaterAlarm(context)
            }
        }
    }

    private fun handleWaterAlarm(context: Context) {
        val prefs = context.getSharedPreferences("neko_offline_reminders", Context.MODE_PRIVATE)
        val intervalMin = prefs.getInt("water_interval_min", 60)
        // Re-arm next slot first so periodic fallback keeps ticking
        if (prefs.getBoolean("water_enabled", false)) {
            OfflineReminderScheduler.armNextAlarm(context, intervalMin)
        }

        // Conflict guard: if Core Hub WS is online or this water job fired recently via Hub, skip!
        if (EventSyncCoordinator.isConnected) {
            Log.d(TAG, "Core Hub WS online; skipping offline AlarmManager water fallback")
            return
        }
        val slotKey = "${OfflineReminderScheduler.JOB_ID_WATER}:${System.currentTimeMillis() / 60_000L}"
        if (EventSyncCoordinator.deduplicator.hasRecentPrefix(OfflineReminderScheduler.JOB_ID_WATER) ||
            !EventSyncCoordinator.deduplicator.tryAccept(slotKey)
        ) {
            Log.d(TAG, "Recent water reminder already delivered; deduplicated")
            return
        }

        val stealth = CharacterStateMachine.stealthOffice.value
        val mode = CharacterStateMachine.mode.value
        CharacterStateMachine.applyDirective(
            CharacterDirective(
                directiveId = slotKey,
                mode = mode,
                state = CharacterStateId.LIFE_WATER,
                stealthOverride = stealth,
                text = if (mode == AssistantMode.SECRETARY) {
                    "离线兜底提示：距离上次补水已过去 ${intervalMin} 分钟，请喝水。"
                } else {
                    "主人，离线闹钟到点啦~ 快喝口水润润喉吧！"
                },
                expression = "care_cup",
                motion = "hold_water_cup",
                costume = if (mode == AssistantMode.SECRETARY) "secretary" else "default",
                ttsAllowed = false,
                ttsCacheKey = null,
                actions = listOf(
                    DirectiveAction("water_done:${OfflineReminderScheduler.JOB_ID_WATER}", "喝完啦", "happy_ear_wiggle"),
                    DirectiveAction("water_snooze_5:${OfflineReminderScheduler.JOB_ID_WATER}", "5m后", "puff_cheeks")
                )
            )
        )
    }

    private companion object {
        const val TAG = "OfflineReminderReceiver"
    }
}
