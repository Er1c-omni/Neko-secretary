package com.neko.secretary.perception

import com.neko.secretary.voice.LipSyncEnvelope
import com.neko.secretary.voice.TtsPhraseCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiStealthAndTtsTest {

    @Test
    fun `WifiStealthEvaluator normalizes quoted SSIDs and enables auto-stealth on work Wi-Fi`() {
        val configured = WifiStealthEvaluator.parseConfiguredSsids("Corp-WiFi, Lab-5G，OfficeNet")
        assertEquals(setOf("corp-wifi", "lab-5g", "officenet"), configured)
        assertNull(WifiStealthEvaluator.normalizeSsid("<unknown ssid>"))

        // Connected to work Wi-Fi without headphones -> auto-stealth ON, TTS strictly muted
        val atWork = WifiStealthEvaluator.evaluate(
            rawSsid = "\"Corp-WiFi\"",
            configuredWorkSsids = configured,
            headphonesConnected = false,
            userTtsEnabled = true,
            manualStealthOffice = false
        )
        assertTrue(atWork.onWorkWifi)
        assertTrue(atWork.shouldAutoStealth)
        assertFalse(atWork.ttsEffectiveAllowed)

        // At home (non-work Wi-Fi) with userTtsEnabled=true -> stealth OFF, TTS allowed
        val atHome = WifiStealthEvaluator.evaluate(
            rawSsid = "\"Home-5G\"",
            configuredWorkSsids = configured,
            headphonesConnected = false,
            userTtsEnabled = true,
            manualStealthOffice = false
        )
        assertFalse(atHome.onWorkWifi)
        assertFalse(atHome.shouldAutoStealth)
        assertTrue(atHome.ttsEffectiveAllowed)

        // Manual stealth overrides TTS even at home with headphones
        val manualStealth = WifiStealthEvaluator.evaluate(
            rawSsid = "\"Home-5G\"",
            configuredWorkSsids = configured,
            headphonesConnected = true,
            userTtsEnabled = true,
            manualStealthOffice = true
        )
        assertFalse(manualStealth.ttsEffectiveAllowed)
    }

    @Test
    fun `TtsPhraseCatalog contains 20+ emotional cached phrases and LipSyncEnvelope maps RMS`() {
        assertTrue("Expected at least 20 cached phrases", TtsPhraseCatalog.PHRASES.size >= 20)
        assertEquals("water_remind_01", TtsPhraseCatalog.find("water_remind_01")?.cacheKey)

        assertEquals(0f, LipSyncEnvelope.rmsToMouthOpen(0.01f), 0.0001f)
        assertTrue(LipSyncEnvelope.rmsToMouthOpen(0.25f) in 0.5f..1.0f)

        val wav = ByteArray(50)
        wav[0] = 'R'.code.toByte(); wav[1] = 'I'.code.toByte(); wav[2] = 'F'.code.toByte(); wav[3] = 'F'.code.toByte()
        assertEquals(6, LipSyncEnvelope.stripWavHeaderIfPresent(wav).size)
    }
}
