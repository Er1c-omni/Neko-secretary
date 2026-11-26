package com.neko.secretary.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceGestureMachineTest {

    @Test
    fun `hold and release after min duration produces SEND`() {
        val fsm = VoiceGestureMachine(cancelThresholdPx = 150f, hysteresisPx = 20f, minDurationMs = 300L)
        assertTrue(fsm.start(anchorRawY = 1000f, nowMs = 1000L))
        assertEquals(VoiceGesturePhase.RECORDING, fsm.phase)
        assertFalse(fsm.onMove(currentRawY = 920f)) // 80px up < 150px
        assertEquals(VoiceGestureOutcome.SEND, fsm.release(nowMs = 1600L))
        assertEquals(VoiceGesturePhase.FINALIZING, fsm.phase)
        fsm.markFinished()
        assertEquals(VoiceGesturePhase.IDLE, fsm.phase)
    }

    @Test
    fun `slide up arms cancel with hysteresis and release cancels`() {
        val fsm = VoiceGestureMachine(cancelThresholdPx = 150f, hysteresisPx = 20f, minDurationMs = 300L)
        fsm.start(anchorRawY = 1000f, nowMs = 1000L)

        // Move up 160px -> CANCEL_ARMED
        assertTrue(fsm.onMove(currentRawY = 840f))
        assertEquals(VoiceGesturePhase.CANCEL_ARMED, fsm.phase)

        // Move slightly down to 140px up (inside hysteresis band 130..150) -> stays CANCEL_ARMED
        assertFalse(fsm.onMove(currentRawY = 860f))
        assertEquals(VoiceGesturePhase.CANCEL_ARMED, fsm.phase)

        // Move back down to 100px up (< 130px) -> returns to RECORDING
        assertTrue(fsm.onMove(currentRawY = 900f))
        assertEquals(VoiceGesturePhase.RECORDING, fsm.phase)

        // Move up again and release -> CANCELLED
        assertTrue(fsm.onMove(currentRawY = 800f))
        assertEquals(VoiceGestureOutcome.CANCELLED, fsm.release(nowMs = 2000L))
        assertEquals(VoiceGesturePhase.IDLE, fsm.phase)
    }

    @Test
    fun `very short hold returns TOO_SHORT`() {
        val fsm = VoiceGestureMachine(minDurationMs = 300L)
        fsm.start(anchorRawY = 1000f, nowMs = 1000L)
        assertEquals(VoiceGestureOutcome.TOO_SHORT, fsm.release(nowMs = 1150L))
        assertEquals(VoiceGesturePhase.IDLE, fsm.phase)
    }

    @Test
    fun `TranscriptCleaner strips Chinese and English fillers cleanly`() {
        assertEquals("每小时提醒我喝水", TranscriptCleaner.clean("嗯，那个，每小时提醒我喝水"))
        assertEquals("提醒我明天面试", TranscriptCleaner.clean("呃 就是说 提醒我明天面试"))
        assertEquals("set a timer", TranscriptCleaner.clean("um, uh, set a timer"))
        assertEquals("今天写完论文", TranscriptCleaner.clean("今天写完论文"))
    }

    @Test
    fun `PcmAudioMath computes 0 for silence and high RMS for loud signal`() {
        val silence = ByteArray(640)
        assertEquals(0f, PcmAudioMath.normalizedRms(silence), 0.0001f)

        val loud = ByteBuffer.allocate(640).order(ByteOrder.LITTLE_ENDIAN)
        repeat(320) { loud.putShort(16384) }
        val rms = PcmAudioMath.normalizedRms(loud.array())
        assertEquals(0.5f, rms, 0.01f)
    }
}
