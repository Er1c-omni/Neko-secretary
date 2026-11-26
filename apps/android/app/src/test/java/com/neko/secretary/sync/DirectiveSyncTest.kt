package com.neko.secretary.sync

import com.neko.secretary.protocol.AssistantMode
import com.neko.secretary.protocol.CharacterStateId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DirectiveSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `DirectiveSyncCodec parses directive_push with quick-reply actions`() {
        val raw = """
            {
              "jsonrpc": "2.0",
              "method": "directive.push",
              "params": {
                "directiveId": "job_water_hourly:1795672800000",
                "mode": "life",
                "state": "LIFE_WATER",
                "stealthOverride": false,
                "text": "主人，该喝水啦~",
                "live2d": { "expression": "care_cup", "motion": "hold_water_cup", "costume": "maid_default" },
                "tts": { "enabledIfAllowed": false, "cacheKey": "water_remind_01" },
                "actions": [
                  { "id": "water_done:job_water_hourly", "label": "喝完啦", "feedbackMotion": "happy_ear_wiggle" },
                  { "id": "water_snooze_5:job_water_hourly", "label": "5m后", "feedbackMotion": "puff_cheeks" }
                ]
              }
            }
        """.trimIndent()

        val directive = DirectiveSyncCodec.parseDirectivePushNotification(raw)
        assertNotNull(directive)
        assertEquals("job_water_hourly:1795672800000", directive!!.directiveId)
        assertEquals(AssistantMode.LIFE, directive.mode)
        assertEquals(CharacterStateId.LIFE_WATER, directive.state)
        assertEquals(2, directive.actions.size)
        assertEquals("喝完啦", directive.actions[0].label)
        assertEquals("5m后", directive.actions[1].label)
    }

    @Test
    fun `DirectiveSyncCodec builds idempotent user_action_reply event`() {
        val json = DirectiveSyncCodec.buildActionReplyEventJson(
            directiveId = "job_water_hourly:12345",
            actionId = "water_done:job_water_hourly",
            intent = "water_reminder",
            eventId = "evt-action-1",
            timestampMs = 1795672800000L
        )
        val obj = JSONObject(json)
        assertEquals("user.action_reply", obj.getString("type"))
        assertEquals("android:action:job_water_hourly:12345:water_done:job_water_hourly", obj.getString("idempotencyKey"))
        assertEquals("water_done:job_water_hourly", obj.getJSONObject("payload").getString("actionId"))
    }

    @Test
    fun `FireKeyDeduplicator blocks duplicate fire_keys and recent job prefixes within window`() {
        val dedup = FireKeyDeduplicator(windowMs = 60_000L)
        assertTrue(dedup.tryAccept("job_water_hourly:1000", nowMs = 10_000L))
        assertFalse(dedup.tryAccept("job_water_hourly:1000", nowMs = 20_000L))
        assertTrue(dedup.hasRecentPrefix("job_water_hourly", nowMs = 30_000L))

        // After window expires, same prefix/key is allowed again
        assertFalse(dedup.hasRecentPrefix("job_water_hourly", nowMs = 80_000L))
        assertTrue(dedup.tryAccept("job_water_hourly:1000", nowMs = 80_000L))
    }

    @Test
    fun `PersistentEventQueue persists FIFO events and deduplicates by idempotencyKey`() {
        val file = File(tempFolder.root, "offline_events.jsonl")
        val queue = PersistentEventQueue(file)

        val e1 = """{"id":"1","idempotencyKey":"k-1","type":"user.action_reply"}"""
        val e2 = """{"id":"2","idempotencyKey":"k-2","type":"user.voice_transcript"}"""

        assertTrue(queue.enqueue(e1))
        assertFalse(queue.enqueue(e1)) // deduplicated!
        assertTrue(queue.enqueue(e2))
        assertEquals(2, queue.size())

        // Re-open from disk (simulates app restart)
        val reopened = PersistentEventQueue(file)
        assertEquals(2, reopened.size())
        assertEquals(e1, reopened.removeFirst())
        assertEquals(e2, reopened.removeFirst())
        assertEquals(0, reopened.size())
    }
}
