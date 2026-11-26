package com.neko.secretary.perception

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Week2PerceptionTest {

    @Test
    fun `ExternalNotificationFilter matches exam, delivery, and AI tasks while ignoring noise`() {
        assertEquals("interview_exam", ExternalNotificationFilter.classify("com.tencent.mm", "牛客笔试通知", "今晚19:00"))
        assertEquals("delivery", ExternalNotificationFilter.classify("com.cainiao", "菜鸟驿站", "您的快递取件码 3-2-104"))
        assertEquals("ai_task", ExternalNotificationFilter.classify("com.termux", "Codex", "build failed on main"))
        assertNull(ExternalNotificationFilter.classify("com.neko.secretary", "面试提醒", "self notification ignored"))
        assertNull(ExternalNotificationFilter.classify("com.shop", "限时大促", "全场5折优惠券"))

        val json = ExternalNotificationFilter.buildNotificationEventJson(
            packageName = "com.tencent.mm",
            title = "面试通知",
            text = "明天14:00",
            category = "interview_exam",
            postedAt = 1795680000000L,
            eventId = "notif-1"
        )
        val obj = JSONObject(json)
        assertEquals("sensor.notification_external", obj.getString("type"))
        assertEquals("interview_exam", obj.getJSONObject("payload").getString("category"))
    }

    @Test
    fun `DistractionDetector triggers after 5 minutes on Bilibili and respects cooldown`() {
        val detector = DistractionDetector(thresholdMs = 5 * 60_000L, cooldownMs = 10 * 60_000L)
        val t0 = 1_000_000L
        assertNull(detector.onSample("tv.danmaku.bili", t0))
        assertNull(detector.onSample("tv.danmaku.bili", t0 + 4 * 60_000L))

        val alert = detector.onSample("tv.danmaku.bili", t0 + 5 * 60_000L)
        assertNotNull(alert)
        assertEquals("B 站", alert!!.appLabel)
        assertEquals(5, alert.continuousMinutes)

        // Cooldown prevents repeated spam
        assertNull(detector.onSample("tv.danmaku.bili", t0 + 6 * 60_000L))

        // Switching back to productive app resets streak
        assertNull(detector.onSample("com.obsidian.app", t0 + 7 * 60_000L))
    }
}
