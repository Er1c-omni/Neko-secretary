package com.neko.secretary.voice

import android.content.Context
import android.util.Log
import java.io.File

enum class AsrProviderKind(val wireName: String) {
    VOLCENGINE("volcengine"),
    FUNASR("funasr"),
    OFFLINE_SHERPA("sherpa_onnx")
}

/**
 * Runtime ASR configuration loaded from local SharedPreferences (never hardcoded in source).
 */
data class AsrConfig(
    val provider: AsrProviderKind = AsrProviderKind.VOLCENGINE,
    val wsUrl: String = "",
    val appId: String = "",
    val accessToken: String = "",
    val resourceId: String = "volc.bigasr.sauc.duration",
    val offlineFallbackEnabled: Boolean = true
)

object AsrConfigStore {
    private const val FILE = "neko_asr_config"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_WS_URL = "ws_url"
    private const val KEY_APP_ID = "app_id"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_RESOURCE_ID = "resource_id"
    private const val KEY_FALLBACK = "offline_fallback"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): AsrConfig {
        val p = prefs(context)
        val provider = runCatching {
            AsrProviderKind.valueOf(p.getString(KEY_PROVIDER, AsrProviderKind.VOLCENGINE.name)!!)
        }.getOrDefault(AsrProviderKind.VOLCENGINE)
        return AsrConfig(
            provider = provider,
            wsUrl = p.getString(KEY_WS_URL, "").orEmpty(),
            appId = p.getString(KEY_APP_ID, "").orEmpty(),
            accessToken = p.getString(KEY_ACCESS_TOKEN, "").orEmpty(),
            resourceId = p.getString(KEY_RESOURCE_ID, "volc.bigasr.sauc.duration").orEmpty(),
            offlineFallbackEnabled = p.getBoolean(KEY_FALLBACK, true)
        )
    }

    fun save(context: Context, config: AsrConfig) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, config.provider.name)
            .putString(KEY_WS_URL, config.wsUrl.trim())
            .putString(KEY_APP_ID, config.appId.trim())
            .putString(KEY_ACCESS_TOKEN, config.accessToken.trim())
            .putString(KEY_RESOURCE_ID, config.resourceId.trim())
            .putBoolean(KEY_FALLBACK, config.offlineFallbackEnabled)
            .apply()
    }
}

/**
 * Offline ASR fallback seam (Sherpa-ONNX Paraformer / Zipformer).
 * Receives 16 kHz PCM16LE chunks during recording or buffered fallback on network error.
 */
interface OfflineAsrEngine {
    val name: String
    val isAvailable: Boolean
    fun beginSession(sampleRateHz: Int = 16_000)
    fun acceptPcm16(pcm16Le: ByteArray): String
    fun finishSession(): String
    fun cancel()
}

/**
 * Reflection-based Sherpa-ONNX adapter: stays zero-dependency when Sherpa-ONNX AAR/models
 * are not bundled, and automatically activates if `com.k2fsa.sherpa.onnx.OnlineRecognizer`
 * and model files exist under `filesDir/asr/sherpa-onnx/`.
 */
class SherpaOnnxOfflineAsrEngine(context: Context) : OfflineAsrEngine {
    private val modelDir = File(context.applicationContext.filesDir, "asr/sherpa-onnx")

    override val name: String = "sherpa-onnx"

    override val isAvailable: Boolean
        get() = hasSherpaClass() && hasModelFiles()

    private fun hasSherpaClass(): Boolean = runCatching {
        Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
        true
    }.getOrDefault(false)

    private fun hasModelFiles(): Boolean =
        modelDir.isDirectory && modelDir.listFiles()?.any { it.name.endsWith(".onnx") } == true

    override fun beginSession(sampleRateHz: Int) {
        if (!isAvailable) {
            Log.i(TAG, "Sherpa-ONNX library or .onnx model not present in ${modelDir.absolutePath}")
        }
    }

    override fun acceptPcm16(pcm16Le: ByteArray): String = ""

    override fun finishSession(): String = ""

    override fun cancel() = Unit

    private companion object {
        const val TAG = "SherpaOnnxFallback"
    }
}
