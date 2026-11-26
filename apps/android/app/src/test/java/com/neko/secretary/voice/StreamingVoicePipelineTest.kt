package com.neko.secretary.voice

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingVoicePipelineTest {

    private class FakeAudioSource(private val chunks: Int = 3) : AudioFrameSource {
        override val sampleRateHz: Int = 16_000
        override fun captureFrames(): Flow<AudioFrame> = flow {
            repeat(chunks) { idx ->
                emit(AudioFrame(ByteArray(320) { 1 }, rms = 0.25f, sequence = idx))
                delay(15)
            }
        }
    }

    private class FakeStreamingBackend(
        private val failOnOpen: Boolean = false,
        private val failOnSend: Boolean = false,
        private val partial: String = "嗯，提醒我",
        private val finalResult: String = "嗯，那个，提醒我喝水"
    ) : StreamingAsrBackend {
        override val providerName: String = "fake-volcengine"
        var sentCount = 0
        var closed = false
        private var partialCb: ((String) -> Unit)? = null

        override suspend fun open(
            onPartial: (String) -> Unit,
            onFinal: (String) -> Unit,
            onError: (String) -> Unit
        ) {
            if (failOnOpen) error("network unreachable")
            partialCb = onPartial
        }

        override suspend fun sendPcm(pcm16Le: ByteArray) {
            if (failOnSend) error("socket broken")
            sentCount++
            partialCb?.invoke(partial)
        }

        override suspend fun finish(): String = finalResult

        override suspend fun close() {
            closed = true
        }
    }

    private class FakeOfflineAsr(
        override val isAvailable: Boolean = true,
        private val transcript: String = "离线识别结果"
    ) : OfflineAsrEngine {
        override val name: String = "fake-sherpa"
        var bytesAccepted = 0
        var cancelled = false

        override fun beginSession(sampleRateHz: Int) {
            bytesAccepted = 0
        }

        override fun acceptPcm16(pcm16Le: ByteArray): String {
            bytesAccepted += pcm16Le.size
            return transcript
        }

        override fun finishSession(): String = transcript

        override fun cancel() {
            cancelled = true
        }
    }

    @Test
    fun `primary streaming happy path cleans fillers and closes backend`() = runBlocking {
        val backend = FakeStreamingBackend()
        val offline = FakeOfflineAsr()
        val pipeline = StreamingVoicePipeline(
            audioSource = FakeAudioSource(chunks = 3),
            configProvider = { AsrConfig(provider = AsrProviderKind.VOLCENGINE, wsUrl = "wss://mock") },
            offlineFallback = offline,
            backendFactory = { backend }
        )

        pipeline.startStreaming()
        delay(80)
        val result = pipeline.finishStreaming()
        pipeline.release()

        assertEquals("提醒我喝水", result)
        assertTrue(backend.sentCount > 0)
        assertTrue(backend.closed)
    }

    @Test
    fun `primary connect failure degrades automatically to offline Sherpa-ONNX fallback`() = runBlocking {
        val backend = FakeStreamingBackend(failOnOpen = true)
        val offline = FakeOfflineAsr(isAvailable = true, transcript = "嗯，离线喝水提醒")
        val pipeline = StreamingVoicePipeline(
            audioSource = FakeAudioSource(chunks = 3),
            configProvider = { AsrConfig(provider = AsrProviderKind.VOLCENGINE, wsUrl = "wss://mock", offlineFallbackEnabled = true) },
            offlineFallback = offline,
            backendFactory = { backend }
        )

        pipeline.startStreaming()
        delay(80)
        val result = pipeline.finishStreaming()
        pipeline.release()

        assertEquals("离线喝水提醒", result)
        assertTrue(offline.bytesAccepted > 0)
    }

    @Test
    fun `mid-stream send failure replays buffered PCM into offline fallback`() = runBlocking {
        val backend = FakeStreamingBackend(failOnSend = true)
        val offline = FakeOfflineAsr(isAvailable = true, transcript = "断网兜底成功")
        val pipeline = StreamingVoicePipeline(
            audioSource = FakeAudioSource(chunks = 3),
            configProvider = { AsrConfig(provider = AsrProviderKind.FUNASR, wsUrl = "wss://mock", offlineFallbackEnabled = true) },
            offlineFallback = offline,
            backendFactory = { backend }
        )

        pipeline.startStreaming()
        delay(80)
        val result = pipeline.finishStreaming()
        pipeline.release()

        assertEquals("断网兜底成功", result)
        assertTrue(offline.bytesAccepted >= 320)
    }

    @Test
    fun `VoiceEventFactory produces valid user_voice_transcript EventEnvelope`() {
        val json = VoiceEventFactory.buildTranscriptEventJson(
            cleanedText = "每小时提醒我喝水",
            rawText = "嗯，每小时提醒我喝水",
            provider = "volcengine",
            durationMs = 1200L,
            eventId = "evt-123",
            timestampMs = 1700000000000L
        )
        val obj = JSONObject(json)
        assertEquals("evt-123", obj.getString("id"))
        assertEquals("android:voice:evt-123", obj.getString("idempotencyKey"))
        assertEquals("user.voice_transcript", obj.getString("type"))
        assertEquals("android", obj.getString("source"))
        assertEquals("每小时提醒我喝水", obj.getJSONObject("payload").getString("text"))
    }
}
