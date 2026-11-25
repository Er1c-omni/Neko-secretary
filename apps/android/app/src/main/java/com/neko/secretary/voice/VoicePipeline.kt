package com.neko.secretary.voice

/**
 * Long-press streaming ASR pipeline (Volcengine / FunASR primary, Sherpa-ONNX offline fallback)
 * plus optional cached TTS playback that auto-mutes in STEALTH_OFFICE mode.
 */
interface VoiceAsrEngine {
    suspend fun startStreaming()
    suspend fun finishStreaming(): String
    fun cancel()
}

interface OptionalTtsEngine {
    fun playCachedOrSynthesize(cacheKey: String?, dynamicText: String?, stealthOffice: Boolean)
}

class StubVoicePipeline : VoiceAsrEngine, OptionalTtsEngine {
    override suspend fun startStreaming() = Unit

    override suspend fun finishStreaming(): String = ""

    override fun cancel() = Unit

    override fun playCachedOrSynthesize(
        cacheKey: String?,
        dynamicText: String?,
        stealthOffice: Boolean
    ) {
        if (stealthOffice) {
            return
        }
        // Week 2: Play pre-generated emotional audio asset or stream from TTS provider.
    }
}
