package com.neko.secretary.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

class AsrProtocolTest {

    @Test
    fun `VolcengineProtocol encodes full client request and audio chunk headers`() {
        val cfg = VolcengineProtocol.buildConfigJson(appId = "test-app")
        val initFrame = VolcengineProtocol.encodeFullClientRequest(cfg, compress = true)
        assertEquals(0x11, initFrame[0].toInt() and 0xFF)
        assertEquals(0x10, initFrame[1].toInt() and 0xFF)
        assertEquals(0x11, initFrame[2].toInt() and 0xFF)

        val pcm = ByteArray(320) { 1 }
        val midChunk = VolcengineProtocol.encodeAudioChunk(pcm, isLast = false, compress = false)
        assertEquals(0x20, midChunk[1].toInt() and 0xFF)

        val lastChunk = VolcengineProtocol.encodeAudioChunk(ByteArray(0), isLast = true, compress = true)
        assertEquals(0x22, lastChunk[1].toInt() and 0xFF)
    }

    @Test
    fun `VolcengineProtocol decodes gzipped server response with sequence and final flag`() {
        val json = """{"result":{"text":"每小时提醒我喝水"}}""".toByteArray(Charsets.UTF_8)
        val gz = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write(json) }
            baos.toByteArray()
        }
        // Header: ver=1, size=1 | type=9 (server), flags=3 (has seq + last) | json + gzip | reserved=0
        val buf = ByteBuffer.allocate(4 + 4 + 4 + gz.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x11)
        buf.put(((0x9 shl 4) or 0x3).toByte())
        buf.put(((0x1 shl 4) or 0x1).toByte())
        buf.put(0x00)
        buf.putInt(-4)
        buf.putInt(gz.size)
        buf.put(gz)

        val decoded = VolcengineProtocol.decodeServerMessage(buf.array())
        assertTrue(decoded is VolcengineServerMessage.Transcript)
        val t = decoded as VolcengineServerMessage.Transcript
        assertEquals("每小时提醒我喝水", t.text)
        assertTrue(t.isFinal)
        assertEquals(-4, t.sequence)
    }

    @Test
    fun `VolcengineProtocol decodes server error frame`() {
        val msg = "invalid token".toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 4 + 4 + msg.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x11)
        buf.put(((0xF shl 4) or 0x0).toByte())
        buf.put(0x00)
        buf.put(0x00)
        buf.putInt(45000001)
        buf.putInt(msg.size)
        buf.put(msg)

        val decoded = VolcengineProtocol.decodeServerMessage(buf.array())
        assertTrue(decoded is VolcengineServerMessage.Error)
        val err = decoded as VolcengineServerMessage.Error
        assertEquals(45000001, err.code)
        assertEquals("invalid token", err.message)
    }

    @Test
    fun `FunAsrProtocol encodes start-stop and parses 2pass responses`() {
        val start = JSONObject(FunAsrProtocol.buildStartJson())
        assertEquals("2pass", start.getString("mode"))
        assertTrue(start.getBoolean("is_speaking"))

        val stop = JSONObject(FunAsrProtocol.buildStopJson())
        assertFalse(stop.getBoolean("is_speaking"))

        val online = FunAsrProtocol.parseServerMessage("""{"mode":"2pass-online","text":"喝水","is_final":false}""")
        assertNotNull(online)
        assertEquals("喝水", online!!.text)
        assertFalse(online.isFinal)

        val offline = FunAsrProtocol.parseServerMessage("""{"mode":"2pass-offline","text":"提醒我喝水","is_final":false}""")
        assertNotNull(offline)
        assertTrue(offline!!.isFinal)
    }
}
