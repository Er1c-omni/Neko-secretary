package com.neko.secretary.sync

/**
 * Pluggable transport seam matching packages/protocol/src/rpc.ts.
 * Defaults to TailscaleDirectTransport; CloudRelayTransport can be added for OSS cloud mode.
 */
interface TransportClient {
    val kind: String
    suspend fun connect(url: String, bearerToken: String?)
    suspend fun sendEvent(eventJson: String)
    suspend fun disconnect()
}

class TailscaleDirectTransport : TransportClient {
    override val kind: String = "tailscale_direct"

    override suspend fun connect(url: String, bearerToken: String?) {
        // Week 1: Connect Ktor WebSocket client to Core Hub over Tailscale mesh.
    }

    override suspend fun sendEvent(eventJson: String) {
        // Week 1: Enqueue locally in Room if offline and replay idempotently on reconnect.
    }

    override suspend fun disconnect() = Unit
}
