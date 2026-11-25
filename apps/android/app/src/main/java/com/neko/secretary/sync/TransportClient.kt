package com.neko.secretary.sync

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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Pluggable transport seam matching packages/protocol/src/rpc.ts.
 * Defaults to TailscaleDirectTransport; CloudRelayTransport can be added for OSS cloud mode.
 */
interface TransportClient {
    val kind: String
    val notifications: SharedFlow<String>
    suspend fun connect(url: String, bearerToken: String?)
    suspend fun ping(): Long
    suspend fun sendEvent(eventJson: String)
    suspend fun disconnect()
}

class TailscaleDirectTransport : TransportClient {
    override val kind: String = "tailscale_direct"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private val queueMutex = Mutex()
    private val offlineQueue = ArrayDeque<String>()
    private val pendingRpc = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val requestSeq = AtomicLong(1L)

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
        if (activeSession == null) {
            queueMutex.withLock {
                offlineQueue.addLast(eventJson)
            }
            return
        }
        try {
            requestRpc("event.ingest", JSONObject(eventJson))
        } catch (_: Exception) {
            queueMutex.withLock {
                offlineQueue.addLast(eventJson)
            }
        }
    }

    override suspend fun disconnect() {
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
        while (true) {
            val nextEvent = queueMutex.withLock {
                if (offlineQueue.isEmpty()) null else offlineQueue.removeFirst()
            } ?: break
            sendEvent(nextEvent)
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
            for ((_, deferred) in pendingRpc) {
                deferred.cancel()
            }
            pendingRpc.clear()
        }
    }
}
