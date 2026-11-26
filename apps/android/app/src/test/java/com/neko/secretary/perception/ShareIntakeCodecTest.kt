package com.neko.secretary.perception

import com.neko.secretary.protocol.AssistantMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntakeCodecTest {

    @Test
    fun `computeIdempotencyKey produces deterministic sha256 prefix`() {
        val k1 = ShareIntakeCodec.computeIdempotencyKey("明天面试", byteArrayOf(1, 2, 3))
        val k2 = ShareIntakeCodec.computeIdempotencyKey(" 明天面试 ", byteArrayOf(1, 2, 3))
        assertEquals(k1, k2)
        assertTrue(k1.startsWith("android:share:"))
        assertEquals("android:share:".length + 64, k1.length)
    }

    @Test
    fun `buildIntakeRequestJson and parseCardFromResponse round-trip cleanly`() {
        val req = ShareIntakeCodec.buildIntakeRequestJson(
            text = "笔试截止今晚 23:59",
            imageBase64 = "ZmFrZQ==",
            imageMimeType = "image/jpeg",
            idempotencyKey = "android:share:abc",
            mode = AssistantMode.SECRETARY,
            stealthOffice = false
        )
        val reqObj = JSONObject(req)
        assertEquals("secretary", reqObj.getString("mode"))
        assertEquals("ZmFrZQ==", reqObj.getString("imageBase64"))

        val resp = """
            {
              "ok": true,
              "card": {
                "cardId": "card-99",
                "kind": "deadline",
                "title": "秋招笔试 DDL",
                "summary": "今晚 23:59 截止",
                "dueAt": 1795701600000,
                "amount": null,
                "currency": null,
                "tags": ["deadline", "screenshot"]
              }
            }
        """.trimIndent()
        val card = ShareIntakeCodec.parseCardFromResponse(resp)
        assertNotNull(card)
        assertEquals("card-99", card!!.cardId)
        assertEquals("deadline", card.kind)
        assertEquals("秋招笔试 DDL", card.title)
        assertEquals(listOf("deadline", "screenshot"), card.tags)
    }

    @Test
    fun `offline fallback classifies bill, deadline, schedule, and inspiration`() {
        assertEquals("bill", ShareIntakeCodec.buildOfflineFallbackCard("咖啡消费 ¥35", false).kind)
        assertEquals("deadline", ShareIntakeCodec.buildOfflineFallbackCard("明天 14:00 面试通知", true).kind)
        assertEquals("schedule", ShareIntakeCodec.buildOfflineFallbackCard("明天项目组会议", false).kind)
        assertEquals("inspiration", ShareIntakeCodec.buildOfflineFallbackCard("写一篇关于透明悬浮窗的博客", false).kind)
    }
}
