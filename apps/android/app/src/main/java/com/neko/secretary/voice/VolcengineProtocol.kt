package com.neko.secretary.voice

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

sealed interface VolcengineServerMessage {
    data class Transcript(val text: String, val isFinal: Boolean, val sequence: Int?) : VolcengineServerMessage
    data class Error(val code: Int, val message: String) : VolcengineServerMessage
    data object Unknown : VolcengineServerMessage
}

/**
 * Pure binary frame codec for Volcengine Streaming ASR (BigModel / SAUC WebSocket protocol).
 * Spec: 4-byte header + optional 4-byte sequence + 4-byte big-endian payload length + payload.
 */
object VolcengineProtocol {
    private const val PROTOCOL_VERSION = 0x1
    private const val HEADER_SIZE_WORDS = 0x1

    const val MSG_FULL_CLIENT_REQUEST = 0x1
    const val MSG_AUDIO_ONLY_REQUEST = 0x2
    const val MSG_FULL_SERVER_RESPONSE = 0x9
    const val MSG_ERROR_RESPONSE = 0xF

    private const val FLAG_NONE = 0x0
    private const val FLAG_HAS_SEQUENCE = 0x1
    private const val FLAG_LAST_PACKET = 0x2

    private const val SERIALIZATION_NONE = 0x0
    private const val SERIALIZATION_JSON = 0x1

    private const val COMPRESSION_NONE = 0x0
    private const val COMPRESSION_GZIP = 0x1

    fun buildConfigJson(
        appId: String,
        userId: String = "neko_android",
        sampleRate: Int = 16_000,
        language: String = "zh-CN"
    ): String {
        val root = JSONObject()
        root.put("user", JSONObject().put("uid", userId))
        root.put(
            "audio",
            JSONObject()
                .put("format", "pcm")
                .put("rate", sampleRate)
                .put("bits", 16)
                .put("channel", 1)
                .put("language", language)
        )
        root.put(
            "request",
            JSONObject()
                .put("model_name", "bigmodel")
                .put("enable_itn", true)
                .put("enable_punc", true)
                .put("show_utterances", false)
                .put("app_id", appId)
        )
        return root.toString()
    }

    fun encodeFullClientRequest(configJson: String, compress: Boolean = true): ByteArray {
        val raw = configJson.toByteArray(Charsets.UTF_8)
        val payload = if (compress) gzip(raw) else raw
        val compression = if (compress) COMPRESSION_GZIP else COMPRESSION_NONE
        return packFrame(
            messageType = MSG_FULL_CLIENT_REQUEST,
            flags = FLAG_NONE,
            serialization = SERIALIZATION_JSON,
            compression = compression,
            payload = payload
        )
    }

    fun encodeAudioChunk(pcm: ByteArray, isLast: Boolean, compress: Boolean = true): ByteArray {
        val payload = if (compress && pcm.isNotEmpty()) gzip(pcm) else pcm
        val compression = if (compress && pcm.isNotEmpty()) COMPRESSION_GZIP else COMPRESSION_NONE
        return packFrame(
            messageType = MSG_AUDIO_ONLY_REQUEST,
            flags = if (isLast) FLAG_LAST_PACKET else FLAG_NONE,
            serialization = SERIALIZATION_NONE,
            compression = compression,
            payload = payload
        )
    }

    fun decodeServerMessage(frame: ByteArray): VolcengineServerMessage {
        if (frame.size < 4) return VolcengineServerMessage.Unknown
        val headerSizeBytes = (frame[0].toInt() and 0x0F) * 4
        if (headerSizeBytes < 4 || frame.size < headerSizeBytes) return VolcengineServerMessage.Unknown

        val messageType = (frame[1].toInt() ushr 4) and 0x0F
        val flags = frame[1].toInt() and 0x0F
        val compression = frame[2].toInt() and 0x0F

        val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        buf.position(headerSizeBytes)

        if (messageType == MSG_ERROR_RESPONSE) {
            if (buf.remaining() < 8) return VolcengineServerMessage.Error(-1, "truncated error frame")
            val code = buf.int
            val msgSize = buf.int.coerceAtLeast(0).coerceAtMost(buf.remaining())
            val msgBytes = ByteArray(msgSize)
            buf.get(msgBytes)
            val decoded = if (compression == COMPRESSION_GZIP) gunzip(msgBytes) else msgBytes
            return VolcengineServerMessage.Error(code, decoded.toString(Charsets.UTF_8))
        }

        if (messageType != MSG_FULL_SERVER_RESPONSE) return VolcengineServerMessage.Unknown

        var sequence: Int? = null
        if ((flags and FLAG_HAS_SEQUENCE) != 0) {
            if (buf.remaining() < 4) return VolcengineServerMessage.Unknown
            sequence = buf.int
        }
        val isFinal = (flags and FLAG_LAST_PACKET) != 0 || (sequence != null && sequence < 0)

        if (buf.remaining() < 4) return VolcengineServerMessage.Transcript("", isFinal, sequence)
        val payloadSize = buf.int.coerceAtLeast(0).coerceAtMost(buf.remaining())
        val payloadBytes = ByteArray(payloadSize)
        buf.get(payloadBytes)
        val raw = if (compression == COMPRESSION_GZIP && payloadBytes.isNotEmpty()) {
            gunzip(payloadBytes)
        } else {
            payloadBytes
        }

        val text = extractTranscriptText(raw.toString(Charsets.UTF_8))
        return VolcengineServerMessage.Transcript(text = text, isFinal = isFinal, sequence = sequence)
    }

    private fun extractTranscriptText(jsonStr: String): String {
        if (jsonStr.isBlank()) return ""
        return runCatching {
            val obj = JSONObject(jsonStr)
            val resultObj = obj.optJSONObject("result")
            if (resultObj != null) {
                return@runCatching resultObj.optString("text", "")
            }
            val resultArr = obj.optJSONArray("result")
            if (resultArr != null && resultArr.length() > 0) {
                return@runCatching resultArr.optJSONObject(0)?.optString("text", "").orEmpty()
            }
            obj.optString("text", "")
        }.getOrDefault("")
    }

    private fun packFrame(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
        payload: ByteArray
    ): ByteArray {
        val out = ByteBuffer.allocate(4 + 4 + payload.size).order(ByteOrder.BIG_ENDIAN)
        out.put(((PROTOCOL_VERSION shl 4) or HEADER_SIZE_WORDS).toByte())
        out.put(((messageType shl 4) or (flags and 0x0F)).toByte())
        out.put(((serialization shl 4) or (compression and 0x0F)).toByte())
        out.put(0)
        out.putInt(payload.size)
        out.put(payload)
        return out.array()
    }

    private fun gzip(input: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(input) }
        return baos.toByteArray()
    }

    private fun gunzip(input: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(input)).use { it.readBytes() }
}
