package com.neko.secretary.voice

import org.json.JSONArray
import org.json.JSONObject

data class FunAsrServerMessage(
    val text: String,
    val mode: String,
    val isFinal: Boolean
)

/**
 * Pure JSON codec for Alibaba FunASR / Paraformer 2-pass WebSocket streaming ASR.
 */
object FunAsrProtocol {
    fun buildStartJson(wavName: String = "neko_voice", sampleRate: Int = 16_000): String =
        JSONObject()
            .put("mode", "2pass")
            .put("chunk_size", JSONArray(listOf(5, 10, 5)))
            .put("wav_name", wavName)
            .put("is_speaking", true)
            .put("wav_format", "pcm")
            .put("audio_fs", sampleRate)
            .put("itn", true)
            .toString()

    fun buildStopJson(): String = JSONObject().put("is_speaking", false).toString()

    fun parseServerMessage(jsonText: String): FunAsrServerMessage? = runCatching {
        val obj = JSONObject(jsonText)
        val text = obj.optString("text", "")
        val mode = obj.optString("mode", "")
        val isFinal = obj.optBoolean("is_final", false) || mode == "2pass-offline"
        FunAsrServerMessage(text = text, mode = mode, isFinal = isFinal)
    }.getOrNull()
}
