package com.neko.secretary.sync

import android.content.Context
import com.neko.secretary.live2d.CharacterStateMachine
import com.neko.secretary.protocol.AssistantMode
import com.neko.secretary.protocol.CharacterDirective
import com.neko.secretary.protocol.CharacterStateId
import com.neko.secretary.protocol.DirectiveAction
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Pluggable transport seam matching packages/protocol/src/rpc.ts.
 * Defaults to TailscaleDirectTransport; CloudRelayTransport can be added for OSS cloud mode.
 */
interface TransportClient {
    val kind: String
    val isConnected: StateFlow<Boolean>
    val notifications: SharedFlow<String>
    suspend fun connect(url: String, bearerToken: String?)
    suspend fun ping(): Long
    suspend fun sendEvent(eventJson: String)
    suspend fun disconnect()
}

/**
 * Disk-backed idempotent event queue (JSONL) so offline feedback / voice events survive app kills
 * and replay cleanly once WebSocket reconnects. Pure JVM file I/O for unit testability.
 */
class PersistentEventQueue(private val file: File) {
    private val lock = Any()

    fun enqueue(eventJson: String): Boolean = synchronized(lock) {
        val key = extractIdempotencyKey(eventJson)
        val existing = readAllInternal()
        if (key != null && existing.any { extractIdempotencyKey(it) == key }) {
            return false
        }
        file.parentFile?.mkdirs()
        val sanitized = eventJson.replace("\n", " ").replace("\r", " ")
        file.appendText(sanitized + "\n", Charsets.UTF_8)
        true
    }

    fun peekAll(): List<String> = synchronized(lock) { readAllInternal() }

    fun removeFirst(): String? = synchronized(lock) {
        val list = readAllInternal().toMutableList()
        if (list.isEmpty()) return null
        val first = list.removeAt(0)
        writeAllInternal(list)
        first
    }

    fun size(): Int = synchronized(lock) { readAllInternal().size }

    fun clear() = synchronized(lock) {
        if (file.exists()) file.writeText("", Charsets.UTF_8)
    }

    private fun readAllInternal(): List<String> {
        if (!file.isFile) return emptyList()
        return file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
    }

    private fun writeAllInternal(lines: List<String>) {
        file.parentFile?.mkdirs()
        if (lines.isEmpty()) {
            file.writeText("", Charsets.UTF_8)
        } else {
            file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        }
    }

    private fun extractIdempotencyKey(json: String): String? = runCatching {
        JSONObject(json).optString("idempotencyKey", "").ifBlank { null }
    }.getOrNull()
}

/**
 * Tracks recently fired `fire_key` / `directiveId` slots so online WS `directive.push`
 * and local `AlarmManager` fallback never double-pop the same reminder.
 */
class FireKeyDeduplicator(private val windowMs: Long = 20L * 60_000L) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun tryAccept(fireKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        prune(nowMs)
        if (seen.containsKey(fireKey)) return false
        seen[fireKey] = nowMs
        return true
    }

    @Synchronized
    fun hasRecentPrefix(jobIdPrefix: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        prune(nowMs)
        return seen.keys.any { it.startsWith("$jobIdPrefix:") }
    }

    private fun prune(nowMs: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value > windowMs) it.remove()
        }
    }
}

/**
 * Pure JSON-RPC codecs for `directive.push` and `user.action_reply`.
 */
object DirectiveSyncCodec {
    fun parseDirectivePushNotification(notificationJson: String): CharacterDirective? = runCatching {
        val root = JSONObject(notificationJson)
        if (root.optString("method") != "directive.push") return@runCatching null
        val p = root.optJSONObject("params") ?: return@runCatching null
        val mode = if (p.optString("mode") == "secretary") AssistantMode.SECRETARY else AssistantMode.LIFE
        val state = runCatching { CharacterStateId.valueOf(p.optString("state", "PEEK_IDLE")) }
            .getOrDefault(CharacterStateId.PEEK_IDLE)
        val live2d = p.optJSONObject("live2d")
        val tts = p.optJSONObject("tts")
        val actionsArr = p.optJSONArray("actions") ?: JSONArray()
        val actions = buildList {
            for (i in 0 until actionsArr.length()) {
                val item = actionsArr.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                val label = item.optString("label", "")
                if (id.isNotBlank() && label.isNotBlank()) {
                    add(
                        DirectiveAction(
                            id = id,
                            label = label,
                            feedbackMotion = item.optString("feedbackMotion", "").ifBlank { null }
                        )
                    )
                }
            }
        }
        CharacterDirective(
            directiveId = p.optString("directiveId", UUID.randomUUID().toString()),
            mode = mode,
            state = state,
            stealthOverride = p.optBoolean("stealthOverride", false),
            text = p.optString("text", ""),
            expression = live2d?.optString("expression", "").orEmpty(),
            motion = live2d?.optString("motion", "").orEmpty(),
            costume = live2d?.optString("costume", "default").orEmpty(),
            ttsAllowed = tts?.optBoolean("enabledIfAllowed", false) ?: false,
            ttsCacheKey = tts?.optString("cacheKey", "")?.ifBlank { null },
            actions = actions
        )
    }.getOrNull()

    fun buildActionReplyEventJson(
        directiveId: String,
        actionId: String,
        intent: String,
        jobId: String? = null,
        latencySec: Int? = null,
        eventId: String = UUID.randomUUID().toString(),
        timestampMs: Long = System.currentTimeMillis()
    ): String {
        val payload = JSONObject()
            .put("directiveId", directiveId)
            .put("actionId", actionId)
            .put("intent", intent)
        if (!jobId.isNullOrBlank()) payload.put("jobId", jobId)
        if (latencySec != null) payload.put("latencySec", latencySec)

        return JSONObject()
            .put("id", eventId)
            .put("idempotencyKey", "android:action:$directiveId:$actionId")
            .put("type", "user.action_reply")
            .put("source", "android")
            .put("ts", timestampMs)
            .put("payload", payload)
            .toString()
    }
}

class TailscaleDirectTransport(
    private val persistentQueue: PersistentEventQueue? = null
) : TransportClient {
    override val kind: String = "tailscale_direct"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private val queueMutex = Mutex()
    private val memoryQueue = ArrayDeque<String>()
    private val pendingRpc = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val requestSeq = AtomicLong(1L)

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _notifications = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val notifications: SharedFlow<String> = _notifications.asSharedFlow()

    private var client: HttpClient? = null
    private var session: DefaultClientWebSocketSession? = null
    private var readerJob: Job? = null

    override suspend fun connect(url: String, bearerToken: String?) {
        disconnect()
        val httpClient = HttpClient(OkHttp) {
            install(WebSockets)
        }
        val wsSession = httpClient.webSocketSession(urlString = url) {
            if (!bearerToken.isNullOrBlank()) {
                header(HttpHeaders.Authorization, "Bearer $bearerToken")
            }
        }
        client = httpClient
        session = wsSession
        _isConnected.value = true
        readerJob = scope.launch {
            runReceiveLoop(wsSession)
        }
        flushOfflineQueue()
    }

    override suspend fun ping(): Long {
        val response = requestRpc("hub.ping", JSONObject())
        val result = response.optJSONObject("result")
        return result?.optLong("serverTs", 0L) ?: 0L
    }

    override suspend fun sendEvent(eventJson: String) {
        val activeSession = session
        if (activeSession == null || !_isConnected.value) {
            enqueueOffline(eventJson)
            return
        }
        try {
            requestRpc("event.ingest", JSONObject(eventJson))
        } catch (_: Exception) {
            enqueueOffline(eventJson)
        }
    }

    override suspend fun disconnect() {
        _isConnected.value = false
        readerJob?.cancel()
        readerJob = null
        runCatching { session?.close() }
        session = null
        client?.close()
        client = null
        for ((_, deferred) in pendingRpc) {
            deferred.cancel()
        }
        pendingRpc.clear()
    }

    private suspend fun enqueueOffline(eventJson: String) {
        queueMutex.withLock {
            if (persistentQueue != null) {
                persistentQueue.enqueue(eventJson)
            } else {
                memoryQueue.addLast(eventJson)
            }
        }
    }

    private suspend fun dequeueOffline(): String? = queueMutex.withLock {
        if (persistentQueue != null) {
            persistentQueue.removeFirst()
        } else {
            if (memoryQueue.isEmpty()) null else memoryQueue.removeFirst()
        }
    }

    private suspend fun requestRpc(method: String, params: JSONObject): JSONObject {
        val activeSession = session ?: error("TransportClient is not connected")
        val id = requestSeq.getAndIncrement().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRpc[id] = deferred

        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        return try {
            sendMutex.withLock {
                activeSession.send(Frame.Text(payload.toString()))
            }
            withTimeout(15_000L) {
                deferred.await()
            }
        } finally {
            pendingRpc.remove(id)
        }
    }

    private suspend fun flushOfflineQueue() {
        while (_isConnected.value) {
            val nextEvent = dequeueOffline() ?: break
            try {
                requestRpc("event.ingest", JSONObject(nextEvent))
            } catch (_: Exception) {
                enqueueOffline(nextEvent)
                break
            }
        }
    }

    private suspend fun runReceiveLoop(wsSession: DefaultClientWebSocketSession) {
        try {
            for (frame in wsSession.incoming) {
                if (frame is Frame.Text) {
                    val json = JSONObject(frame.readText())
                    val id = if (json.has("id") && !json.isNull("id")) json.get("id").toString() else null
                    if (id != null) {
                        pendingRpc.remove(id)?.complete(json)
                    } else if (json.has("method")) {
                        _notifications.tryEmit(json.toString())
                    }
                }
            }
        } finally {
            _isConnected.value = false
            for ((_, deferred) in pendingRpc) {
                deferred.cancel()
            }
            pendingRpc.clear()
        }
    }
}

/**
 * Process-wide coordinator bridging Core Hub WS (`directive.push`), persistent offline queue,
 * and `FireKeyDeduplicator` so online reminders and `AlarmManager` offline fallback never collide.
 */
object EventSyncCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val deduplicator = FireKeyDeduplicator()

    @Volatile
    private var transport: TailscaleDirectTransport? = null
    private var collectorJob: Job? = null

    val isConnected: Boolean
        get() = transport?.isConnected?.value == true

    @Synchronized
    fun ensureInitialized(context: Context): TailscaleDirectTransport {
        transport?.let { return it }
        val queueFile = File(context.applicationContext.filesDir, "sync/offline_events.jsonl")
        val created = TailscaleDirectTransport(PersistentEventQueue(queueFile))
        transport = created
        collectorJob = scope.launch {
            created.notifications.collect { raw ->
                val directive = DirectiveSyncCodec.parseDirectivePushNotification(raw) ?: return@collect
                if (deduplicator.tryAccept(directive.directiveId)) {
                    CharacterStateMachine.applyDirective(directive)
                }
            }
        }
        return created
    }

    fun connect(context: Context, wsUrl: String, bearerToken: String?) {
        val client = ensureInitialized(context)
        scope.launch {
            runCatching { client.connect(wsUrl, bearerToken) }
        }
    }

    fun sendEventAsync(context: Context, eventJson: String) {
        val client = ensureInitialized(context)
        scope.launch { client.sendEvent(eventJson) }
    }

    fun sendActionReply(
        context: Context,
        directive: CharacterDirective,
        action: DirectiveAction
    ) {
        val intent = when (directive.state) {
            CharacterStateId.LIFE_WATER -> "water_reminder"
            CharacterStateId.LIFE_STANDUP -> "sedentary_alert"
            CharacterStateId.SECRETARY_ALERT -> "deadline_alert"
            else -> "generic_reply"
        }
        val eventJson = DirectiveSyncCodec.buildActionReplyEventJson(
            directiveId = directive.directiveId,
            actionId = action.id,
            intent = intent
        )
        sendEventAsync(context, eventJson)
    }
}
