package com.neko.secretary.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

data class TtsPhraseEntry(
    val cacheKey: String,
    val emotion: String,
    val text: String
)

/**
 * 24 pre-defined emotional short phrases for 0-latency, 0-token CosyVoice / Doubao cached audio
 * playback (`filesDir/tts_cache/<cacheKey>.wav` or `.pcm`).
 */
object TtsPhraseCatalog {
    val PHRASES: List<TtsPhraseEntry> = listOf(
        TtsPhraseEntry("morning_wfh_01", "happy", "早安主人，居家办公模式已开启，今天也要元气满满哦"),
        TtsPhraseEntry("morning_holiday_01", "happy", "放假啦，好好睡个懒觉享受假期吧"),
        TtsPhraseEntry("water_remind_01", "care", "主人，已经盯着屏幕一小时啦，快喝口水吧"),
        TtsPhraseEntry("water_remind_02", "care", "耳朵都要脱水蔫掉咯，咕咚喝一口水好不好"),
        TtsPhraseEntry("water_done_01", "happy", "好耶，补水成功，奖励一颗小红花"),
        TtsPhraseEntry("water_snooze_01", "pout", "好吧，五分钟后我再来抓你喝水哦"),
        TtsPhraseEntry("standup_01", "care", "久坐超过一小时啦，站起来伸个懒腰吧"),
        TtsPhraseEntry("standup_02", "care", "活动一下肩颈，效率会更高哦"),
        TtsPhraseEntry("focus_catch_01", "pout", "抓到你在摸鱼刷视频啦，快回到正事上来"),
        TtsPhraseEntry("focus_encourage_01", "happy", "专注状态超棒，继续保持"),
        TtsPhraseEntry("deadline_24h_01", "serious", "提醒一下，二十四小时内有重要截止时间"),
        TtsPhraseEntry("deadline_2h_01", "serious", "还有两小时截止，记得检查提交进度"),
        TtsPhraseEntry("deadline_30m_01", "alert", "三十分钟后开始，请提前做好准备"),
        TtsPhraseEntry("share_saved_01", "happy", "截图内容已经帮你归档入库啦"),
        TtsPhraseEntry("share_parsed_01", "happy", "解析好啦，点一下就能一键入库"),
        TtsPhraseEntry("secretary_ready_01", "calm", "指挥官，秘书模式已就绪，请下达指令"),
        TtsPhraseEntry("secretary_alert_01", "alert", "指挥官，远端主机任务有告警，请立即查看"),
        TtsPhraseEntry("approval_needed_01", "serious", "检测到高风险操作，等待你的安全审批"),
        TtsPhraseEntry("sleepy_night_01", "sleepy", "已经很晚啦，早点休息明天会更有精神"),
        TtsPhraseEntry("sleepy_night_02", "sleepy", "再熬夜猫耳朵要打结咯，快去睡觉吧"),
        TtsPhraseEntry("voice_listening_01", "happy", "我在听呢，请说"),
        TtsPhraseEntry("voice_done_01", "happy", "收到啦，马上帮你安排"),
        TtsPhraseEntry("task_done_01", "happy", "太棒了，又完成一项任务"),
        TtsPhraseEntry("greeting_idle_01", "happy", "喵，我就在屏幕边上陪着你哦")
    )

    private val byKey = PHRASES.associateBy { it.cacheKey }

    fun find(cacheKey: String?): TtsPhraseEntry? = cacheKey?.let { byKey[it] }
}

/**
 * Pure lip-sync envelope helper: converts raw PCM16LE RMS to smoothed Live2D ParamMouthOpenY (0..1).
 */
object LipSyncEnvelope {
    fun rmsToMouthOpen(rms: Float, gain: Float = 3.2f, noiseFloor: Float = 0.02f): Float {
        if (rms <= noiseFloor) return 0f
        return ((rms - noiseFloor) * gain).coerceIn(0f, 1f)
    }

    /** Strips standard 44-byte WAV header if present so AudioTrack receives pure PCM16LE samples. */
    fun stripWavHeaderIfPresent(bytes: ByteArray): ByteArray {
        if (bytes.size > 44 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
        ) {
            return bytes.copyOfRange(44, bytes.size)
        }
        return bytes
    }
}

/**
 * Optional cached TTS player with RMS lip-sync output.
 *
 * Hard invariant (AGENTS.md §2 #8):
 * - Auto-mutes in STEALTH_OFFICE or whenever user/Wi-Fi policy disallows TTS.
 * - Prefers local cached CosyVoice/Doubao audio (`filesDir/tts_cache/<cacheKey>.{wav,pcm}`),
 *   computing real-time RMS per 40ms chunk to drive Live2D mouth open parameter.
 * - Falls back cleanly to Android system TextToSpeech + synthetic cadence envelope when audio
 *   cache files have not been populated yet.
 */
class CachedTtsPlayer(
    context: Context,
    private val onMouthOpenChanged: (Float) -> Unit
) : OptionalTtsEngine {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, "tts_cache")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playJob: Job? = null
    private var systemTts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    @Volatile
    var userEnabled: Boolean = false

    override fun playCachedOrSynthesize(
        cacheKey: String?,
        dynamicText: String?,
        stealthOffice: Boolean
    ) {
        if (stealthOffice || !userEnabled) {
            stop()
            return
        }
        val cachedFile = resolveCacheFile(cacheKey)
        if (cachedFile != null) {
            playPcmFileWithLipSync(cachedFile)
            return
        }
        val text = dynamicText?.takeIf { it.isNotBlank() }
            ?: TtsPhraseCatalog.find(cacheKey)?.text
            ?: return
        playSystemTtsWithEnvelope(text)
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        runCatching { systemTts?.stop() }
        onMouthOpenChanged(0f)
    }

    fun release() {
        stop()
        runCatching { systemTts?.shutdown() }
        systemTts = null
        scope.cancel()
    }

    private fun resolveCacheFile(cacheKey: String?): File? {
        if (cacheKey.isNullOrBlank()) return null
        val wav = File(cacheDir, "$cacheKey.wav")
        if (wav.isFile) return wav
        val pcm = File(cacheDir, "$cacheKey.pcm")
        if (pcm.isFile) return pcm
        return null
    }

    private fun playPcmFileWithLipSync(file: File) {
        stop()
        playJob = scope.launch {
            val pcm = runCatching { LipSyncEnvelope.stripWavHeaderIfPresent(file.readBytes()) }
                .getOrNull() ?: return@launch
            if (pcm.isEmpty()) return@launch

            val sampleRate = 16_000
            val chunkBytes = (sampleRate * 2 * 40) / 1000 // 40ms frames -> 25 Hz mouth updates
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(chunkBytes * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            try {
                track.play()
                var offset = 0
                while (isActive && offset < pcm.size) {
                    val len = minOf(chunkBytes, pcm.size - offset)
                    val slice = pcm.copyOfRange(offset, offset + len)
                    val rms = PcmAudioMath.normalizedRms(slice, len)
                    onMouthOpenChanged(LipSyncEnvelope.rmsToMouthOpen(rms))
                    track.write(slice, 0, len)
                    offset += len
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Cached PCM playback failed", t)
            } finally {
                onMouthOpenChanged(0f)
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    private fun playSystemTtsWithEnvelope(text: String) {
        stop()
        ensureSystemTts()
        playJob = scope.launch {
            if (ttsReady) {
                runCatching {
                    systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "neko_tts")
                }
            }
            // Drive smooth syllable cadence mouth animation matching speech length
            val durationMs = (text.length * 140L).coerceIn(800L, 4500L)
            val steps = (durationMs / 40L).toInt()
            try {
                for (i in 0 until steps) {
                    if (!isActive) break
                    val t = i * 0.45
                    val open = (0.25 + 0.65 * abs(sin(t))).toFloat().coerceIn(0f, 1f)
                    onMouthOpenChanged(open)
                    delay(40L)
                }
            } finally {
                onMouthOpenChanged(0f)
            }
        }
    }

    private fun ensureSystemTts() {
        if (systemTts != null) return
        systemTts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                runCatching { systemTts?.language = Locale.SIMPLIFIED_CHINESE }
            }
        }
    }

    private companion object {
        const val TAG = "CachedTtsPlayer"
    }
}
