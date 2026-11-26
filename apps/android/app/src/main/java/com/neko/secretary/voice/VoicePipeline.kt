package com.neko.secretary.voice

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Long-press streaming ASR pipeline (Volcengine / FunASR primary, Sherpa-ONNX offline fallback)
 * plus optional cached TTS playback that auto-mutes in STEALTH_OFFICE mode.
 */
interface VoiceAsrEngine {
    val sessionState: StateFlow<VoiceSessionState>
    suspend fun startStreaming()
    suspend fun finishStreaming(): String
    fun cancel()
}

interface OptionalTtsEngine {
    fun playCachedOrSynthesize(cacheKey: String?, dynamicText: String?, stealthOffice: Boolean)
}

data class VoiceSessionState(
    val active: Boolean = false,
    val finalizing: Boolean = false,
    val partialText: String = "",
    val finalText: String = "",
    val rmsLevel: Float = 0f,
    val activeProvider: String = "idle",
    val errorMessage: String? = null
)

/**
 * Low-level streaming backend contract implemented by Volcengine and FunASR WebSocket clients.
 */
interface StreamingAsrBackend {
    val providerName: String
    suspend fun open(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit)
    suspend fun sendPcm(pcm16Le: ByteArray)
    suspend fun finish(): String
    suspend fun close()
}

/**
 * Builds standard EventEnvelope JSON for `user.voice_transcript` (docs/PROTOCOL.md §2).
 */
object VoiceEventFactory {
    fun buildTranscriptEventJson(
        cleanedText: String,
        rawText: String,
        provider: String,
        durationMs: Long,
        eventId: String = UUID.randomUUID().toString(),
        timestampMs: Long = System.currentTimeMillis()
    ): String {
        val payload = JSONObject()
            .put("text", cleanedText)
            .put("rawText", rawText)
            .put("provider", provider)
            .put("durationMs", durationMs)
        return JSONObject()
            .put("id", eventId)
            .put("idempotencyKey", "android:voice:$eventId")
            .put("type", "user.voice_transcript")
            .put("source", "android")
            .put("ts", timestampMs)
            .put("payload", payload)
            .toString()
    }
}

/**
 * Full streaming ASR pipeline with automatic offline Sherpa-ONNX fallback.
 *
 * Lifecycle:
 * 1. [startStreaming] opens primary streaming backend (Volcengine or FunASR) if configured; if
 *    unconfigured or unreachable, degrades cleanly to [offlineFallback].
 * 2. Audio frames from [audioSource] stream to the active backend while updating [sessionState]
 *    RMS & partial transcript in real time (and buffering PCM in case mid-stream fallback is needed).
 * 3. [finishStreaming] flushes the final frame, strips filler words via [TranscriptCleaner], and
 *    returns the clean transcript.
 * 4. [cancel] aborts capture & closes the socket immediately without returning a transcript.
 */
class StreamingVoicePipeline(
    private val audioSource: AudioFrameSource,
    private val configProvider: () -> AsrConfig,
    private val offlineFallback: OfflineAsrEngine,
    private val backendFactory: (AsrConfig) -> StreamingAsrBackend? = { defaultBackendFor(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : VoiceAsrEngine, OptionalTtsEngine {

    private val _sessionState = MutableStateFlow(VoiceSessionState())
    override val sessionState: StateFlow<VoiceSessionState> = _sessionState.asStateFlow()

    private var captureJob: Job? = null
    private var activeBackend: StreamingAsrBackend? = null
    private var usingOffline = false
    private val pcmBuffer = ByteArrayOutputStream()
    @Volatile private var latestPartial = ""
    @Volatile private var latestFinal = ""

    override suspend fun startStreaming() {
        cancel()
        latestPartial = ""
        latestFinal = ""
        pcmBuffer.reset()
        usingOffline = false

        val config = configProvider()
        val backend = if (config.provider != AsrProviderKind.OFFLINE_SHERPA) backendFactory(config) else null

        if (backend != null) {
            val opened = runCatching {
                backend.open(
                    onPartial = { text ->
                        if (text.isNotBlank()) {
                            latestPartial = text
                            _sessionState.value = _sessionState.value.copy(partialText = text)
                        }
                    },
                    onFinal = { text ->
                        if (text.isNotBlank()) {
                            latestFinal = text
                            _sessionState.value = _sessionState.value.copy(partialText = text)
                        }
                    },
                    onError = { msg ->
                        Log.w(TAG, "Primary ASR stream error: $msg")
                        _sessionState.value = _sessionState.value.copy(errorMessage = msg)
                    }
                )
            }
            if (opened.isSuccess) {
                activeBackend = backend
            } else {
                Log.w(TAG, "Primary ASR connect failed, checking offline fallback", opened.exceptionOrNull())
                runCatching { backend.close() }
                activateOfflineOrMarkUnconfigured(config, opened.exceptionOrNull()?.message)
            }
        } else {
            activateOfflineOrMarkUnconfigured(config, null)
        }

        val providerLabel = activeBackend?.providerName
            ?: if (usingOffline) offlineFallback.name else "unconfigured"
        _sessionState.value = VoiceSessionState(
            active = true,
            finalizing = false,
            activeProvider = providerLabel,
            errorMessage = _sessionState.value.errorMessage
        )

        captureJob = scope.launch {
            runCatching {
                audioSource.captureFrames().collect { frame ->
                    synchronized(pcmBuffer) {
                        if (pcmBuffer.size() < MAX_BUFFERED_PCM_BYTES) {
                            pcmBuffer.write(frame.pcm16Le)
                        }
                    }
                    _sessionState.value = _sessionState.value.copy(rmsLevel = frame.rms)

                    val currentBackend = activeBackend
                    if (currentBackend != null) {
                        val sent = runCatching { currentBackend.sendPcm(frame.pcm16Le) }
                        if (sent.isFailure) {
                            Log.w(TAG, "Streaming send failed, degrading to offline fallback", sent.exceptionOrNull())
                            runCatching { currentBackend.close() }
                            activeBackend = null
                            if (config.offlineFallbackEnabled && offlineFallback.isAvailable) {
                                switchToOfflineMidStream()
                            } else {
                                _sessionState.value = _sessionState.value.copy(
                                    errorMessage = sent.exceptionOrNull()?.message ?: "ASR stream disconnected"
                                )
                            }
                        }
                    } else if (usingOffline) {
                        val partial = offlineFallback.acceptPcm16(frame.pcm16Le)
                        if (partial.isNotBlank()) {
                            latestPartial = partial
                            _sessionState.value = _sessionState.value.copy(partialText = partial)
                        }
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "Audio capture stopped with error", err)
                _sessionState.value = _sessionState.value.copy(errorMessage = err.message)
            }
        }
    }

    override suspend fun finishStreaming(): String {
        captureJob?.cancel()
        captureJob = null
        _sessionState.value = _sessionState.value.copy(active = false, finalizing = true, rmsLevel = 0f)

        val rawTranscript = when {
            activeBackend != null -> {
                val backend = activeBackend!!
                activeBackend = null
                val finished = runCatching {
                    withTimeoutOrNull(FINISH_TIMEOUT_MS) { backend.finish() }
                }.getOrNull().orEmpty()
                runCatching { backend.close() }
                finished.ifBlank { latestFinal.ifBlank { latestPartial } }
            }
            usingOffline -> {
                usingOffline = false
                offlineFallback.finishSession().ifBlank { latestPartial }
            }
            else -> latestFinal.ifBlank { latestPartial }
        }

        val cleaned = TranscriptCleaner.clean(rawTranscript)
        _sessionState.value = _sessionState.value.copy(
            active = false,
            finalizing = false,
            finalText = cleaned,
            partialText = cleaned
        )
        return cleaned
    }

    override fun cancel() {
        captureJob?.cancel()
        captureJob = null
        val backend = activeBackend
        activeBackend = null
        if (backend != null) {
            scope.launch { runCatching { backend.close() } }
        }
        if (usingOffline) {
            usingOffline = false
            offlineFallback.cancel()
        }
        pcmBuffer.reset()
        latestPartial = ""
        latestFinal = ""
        _sessionState.value = VoiceSessionState(active = false, finalizing = false)
    }

    override fun playCachedOrSynthesize(
        cacheKey: String?,
        dynamicText: String?,
        stealthOffice: Boolean
    ) {
        if (stealthOffice) return
        // Week 2 Day 9: Play pre-generated emotional audio asset or stream from TTS provider.
    }

    fun release() {
        cancel()
        scope.cancel()
    }

    private fun activateOfflineOrMarkUnconfigured(config: AsrConfig, reason: String?) {
        if (config.offlineFallbackEnabled && offlineFallback.isAvailable) {
            offlineFallback.beginSession(audioSource.sampleRateHz)
            usingOffline = true
        } else {
            val hint = reason ?: if (config.wsUrl.isBlank()) {
                "请先在控制台配置 Volcengine / FunASR 地址，或安装 Sherpa-ONNX 离线模型"
            } else {
                "ASR 不可用"
            }
            _sessionState.value = _sessionState.value.copy(errorMessage = hint)
        }
    }

    private fun switchToOfflineMidStream() {
        offlineFallback.beginSession(audioSource.sampleRateHz)
        usingOffline = true
        val buffered = synchronized(pcmBuffer) { pcmBuffer.toByteArray() }
        if (buffered.isNotEmpty()) {
            val partial = offlineFallback.acceptPcm16(buffered)
            if (partial.isNotBlank()) latestPartial = partial
        }
        _sessionState.value = _sessionState.value.copy(activeProvider = offlineFallback.name)
    }

    companion object {
        private const val TAG = "VoicePipeline"
        private const val FINISH_TIMEOUT_MS = 4_000L
        private const val MAX_BUFFERED_PCM_BYTES = 16_000 * 2 * 30 // 30s fallback buffer

        fun create(context: Context): StreamingVoicePipeline = StreamingVoicePipeline(
            audioSource = AndroidAudioRecordSource(),
            configProvider = { AsrConfigStore.load(context) },
            offlineFallback = SherpaOnnxOfflineAsrEngine(context)
        )

        fun defaultBackendFor(config: AsrConfig): StreamingAsrBackend? {
            if (config.wsUrl.isBlank()) return null
            return when (config.provider) {
                AsrProviderKind.VOLCENGINE -> VolcengineWsAsrBackend(config)
                AsrProviderKind.FUNASR -> FunAsrWsAsrBackend(config)
                AsrProviderKind.OFFLINE_SHERPA -> null
            }
        }
    }
}

/**
 * Ktor WebSocket client implementing Volcengine BigModel / SAUC binary streaming ASR.
 */
class VolcengineWsAsrBackend(
    private val config: AsrConfig
) : StreamingAsrBackend {
    override val providerName: String = "volcengine"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: HttpClient? = null
    private var session: DefaultClientWebSocketSession? = null
    private var readerJob: Job? = null
    @Volatile private var latestText = ""
    private var finalSignal: kotlinx.coroutines.CompletableDeferred<String>? = null

    override suspend fun open(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val http = HttpClient(OkHttp) { install(WebSockets) }
        val connectId = UUID.randomUUID().toString()
        val ws = http.webSocketSession(urlString = config.wsUrl) {
            if (config.appId.isNotBlank()) header("X-Api-App-Key", config.appId)
            if (config.accessToken.isNotBlank()) header("X-Api-Access-Key", config.accessToken)
            if (config.resourceId.isNotBlank()) header("X-Api-Resource-Id", config.resourceId)
            header("X-Api-Connect-Id", connectId)
        }
        client = http
        session = ws
        finalSignal = kotlinx.coroutines.CompletableDeferred()

        val initJson = VolcengineProtocol.buildConfigJson(appId = config.appId)
        ws.send(Frame.Binary(true, VolcengineProtocol.encodeFullClientRequest(initJson)))

        readerJob = scope.launch {
            runCatching {
                for (frame in ws.incoming) {
                    if (frame is Frame.Binary) {
                        when (val msg = VolcengineProtocol.decodeServerMessage(frame.readBytes())) {
                            is VolcengineServerMessage.Transcript -> {
                                if (msg.text.isNotBlank()) latestText = msg.text
                                if (msg.isFinal) {
                                    onFinal(latestText)
                                    finalSignal?.complete(latestText)
                                } else {
                                    onPartial(latestText)
                                }
                            }
                            is VolcengineServerMessage.Error -> {
                                onError("Volcengine(${msg.code}): ${msg.message}")
                                finalSignal?.complete(latestText)
                            }
                            VolcengineServerMessage.Unknown -> Unit
                        }
                    }
                }
            }.onFailure { err ->
                onError(err.message ?: "Volcengine WS closed")
            }
            finalSignal?.complete(latestText)
        }
    }

    override suspend fun sendPcm(pcm16Le: ByteArray) {
        val ws = session ?: error("Volcengine WS not connected")
        ws.send(Frame.Binary(true, VolcengineProtocol.encodeAudioChunk(pcm16Le, isLast = false)))
    }

    override suspend fun finish(): String {
        val ws = session ?: return latestText
        runCatching {
            ws.send(Frame.Binary(true, VolcengineProtocol.encodeAudioChunk(ByteArray(0), isLast = true)))
        }
        return finalSignal?.await().orEmpty().ifBlank { latestText }
    }

    override suspend fun close() {
        readerJob?.cancel()
        readerJob = null
        runCatching { session?.close() }
        session = null
        client?.close()
        client = null
        scope.cancel()
    }
}

/**
 * Ktor WebSocket client implementing Alibaba FunASR / Paraformer 2-pass streaming ASR.
 */
class FunAsrWsAsrBackend(
    private val config: AsrConfig
) : StreamingAsrBackend {
    override val providerName: String = "funasr"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: HttpClient? = null
    private var session: DefaultClientWebSocketSession? = null
    private var readerJob: Job? = null
    private val finalizedChunks = StringBuilder()
    @Volatile private var currentOnline = ""
    private var finalSignal: kotlinx.coroutines.CompletableDeferred<String>? = null

    override suspend fun open(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val http = HttpClient(OkHttp) { install(WebSockets) }
        val ws = http.webSocketSession(urlString = config.wsUrl) {
            if (config.accessToken.isNotBlank()) {
                header("Authorization", "Bearer ${config.accessToken}")
            }
        }
        client = http
        session = ws
        finalSignal = kotlinx.coroutines.CompletableDeferred()

        ws.send(Frame.Text(FunAsrProtocol.buildStartJson()))

        readerJob = scope.launch {
            runCatching {
                for (frame in ws.incoming) {
                    if (frame is Frame.Text) {
                        val msg = FunAsrProtocol.parseServerMessage(frame.readText()) ?: continue
                        if (msg.isFinal) {
                            if (msg.text.isNotBlank()) finalizedChunks.append(msg.text)
                            currentOnline = ""
                            val combined = finalizedChunks.toString()
                            onFinal(combined)
                            finalSignal?.complete(combined)
                        } else {
                            currentOnline = msg.text
                            onPartial(finalizedChunks.toString() + currentOnline)
                        }
                    }
                }
            }.onFailure { err ->
                onError(err.message ?: "FunASR WS closed")
            }
            finalSignal?.complete(finalizedChunks.toString() + currentOnline)
        }
    }

    override suspend fun sendPcm(pcm16Le: ByteArray) {
        val ws = session ?: error("FunASR WS not connected")
        ws.send(Frame.Binary(true, pcm16Le))
    }

    override suspend fun finish(): String {
        val ws = session ?: return finalizedChunks.toString() + currentOnline
        runCatching { ws.send(Frame.Text(FunAsrProtocol.buildStopJson())) }
        return finalSignal?.await().orEmpty().ifBlank { finalizedChunks.toString() + currentOnline }
    }

    override suspend fun close() {
        readerJob?.cancel()
        readerJob = null
        runCatching { session?.close() }
        session = null
        client?.close()
        client = null
        scope.cancel()
    }
}

/** Lightweight stub kept for tests / callers that do not need audio capture. */
class StubVoicePipeline : VoiceAsrEngine, OptionalTtsEngine {
    override val sessionState: StateFlow<VoiceSessionState> = MutableStateFlow(VoiceSessionState())
    override suspend fun startStreaming() = Unit
    override suspend fun finishStreaming(): String = ""
    override fun cancel() = Unit
    override fun playCachedOrSynthesize(
        cacheKey: String?,
        dynamicText: String?,
        stealthOffice: Boolean
    ) {
        if (stealthOffice) return
    }
}
