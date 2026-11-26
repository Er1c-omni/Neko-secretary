package com.neko.secretary.perception

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.neko.secretary.overlay.StealthController
import com.neko.secretary.sync.EventSyncCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

data class WifiStealthDecision(
    val normalizedSsid: String?,
    val onWorkWifi: Boolean,
    val shouldAutoStealth: Boolean,
    val ttsEffectiveAllowed: Boolean
)

/**
 * Pure Wi-Fi stealth & TTS mute policy (JVM-testable).
 * Enforces AGENTS.md Invariant #8: TTS auto-mutes in STEALTH_OFFICE or on work Wi-Fi without headphones.
 */
object WifiStealthEvaluator {
    fun normalizeSsid(rawSsid: String?): String? {
        if (rawSsid.isNullOrBlank()) return null
        val stripped = rawSsid.trim().removePrefix("\"").removeSuffix("\"").trim()
        if (stripped.isBlank() || stripped.equals("<unknown ssid>", ignoreCase = true)) return null
        return stripped
    }

    fun parseConfiguredSsids(csv: String): Set<String> =
        csv.split(',', ';', '\n', '，')
            .mapNotNull { normalizeSsid(it) }
            .map { it.lowercase() }
            .toSet()

    fun evaluate(
        rawSsid: String?,
        configuredWorkSsids: Set<String>,
        headphonesConnected: Boolean,
        userTtsEnabled: Boolean,
        manualStealthOffice: Boolean
    ): WifiStealthDecision {
        val ssid = normalizeSsid(rawSsid)
        val onWorkWifi = ssid != null && configuredWorkSsids.contains(ssid.lowercase())
        val shouldAutoStealth = onWorkWifi
        val effectiveStealth = manualStealthOffice || shouldAutoStealth
        val ttsAllowed = userTtsEnabled && !effectiveStealth && (!onWorkWifi || headphonesConnected)
        return WifiStealthDecision(
            normalizedSsid = ssid,
            onWorkWifi = onWorkWifi,
            shouldAutoStealth = shouldAutoStealth,
            ttsEffectiveAllowed = ttsAllowed
        )
    }
}

/**
 * User-configured company/lab Wi-Fi SSIDs (never hardcoded in source, per ENGINEERING_SPEC §1.3).
 */
object WifiStealthConfigStore {
    private const val FILE = "neko_wifi_stealth"
    private const val KEY_SSIDS = "work_wifi_ssids"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun loadRawCsv(context: Context): String = prefs(context).getString(KEY_SSIDS, "").orEmpty()

    fun loadSsidSet(context: Context): Set<String> =
        WifiStealthEvaluator.parseConfiguredSsids(loadRawCsv(context))

    fun saveRawCsv(context: Context, csv: String) {
        prefs(context).edit().putString(KEY_SSIDS, csv.trim()).apply()
    }
}

/**
 * Monitors Wi-Fi network changes to auto-switch STEALTH_OFFICE when connected to configured
 * office/lab Wi-Fi SSIDs and emits `sensor.wifi_changed` to Core Hub.
 */
class WifiMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private val _currentSsid = MutableStateFlow<String?>(null)
    val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

    private val _onWorkWifi = MutableStateFlow(false)
    val onWorkWifi: StateFlow<Boolean> = _onWorkWifi.asStateFlow()

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val ssid = extractSsid(capabilities)
            evaluateAndApply(ssid)
        }

        override fun onLost(network: Network) {
            evaluateAndApply(null)
        }
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
            registered = true
        }
        evaluateCurrent()
    }

    fun stop() {
        if (!registered) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        registered = false
    }

    fun evaluateCurrent() {
        val active = connectivityManager.activeNetwork
        val caps = active?.let { connectivityManager.getNetworkCapabilities(it) }
        evaluateAndApply(caps?.let { extractSsid(it) })
    }

    fun areHeadphonesConnected(): Boolean {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any { dev ->
            when (dev.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET -> true
                else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        }
    }

    private fun evaluateAndApply(rawSsid: String?) {
        val decision = WifiStealthEvaluator.evaluate(
            rawSsid = rawSsid,
            configuredWorkSsids = WifiStealthConfigStore.loadSsidSet(appContext),
            headphonesConnected = areHeadphonesConnected(),
            userTtsEnabled = true,
            manualStealthOffice = false
        )
        val changed = decision.onWorkWifi != _onWorkWifi.value || decision.normalizedSsid != _currentSsid.value
        _currentSsid.value = decision.normalizedSsid
        _onWorkWifi.value = decision.onWorkWifi

        if (decision.shouldAutoStealth) {
            StealthController.set(appContext, true)
        }

        if (changed) {
            val eventId = UUID.randomUUID().toString()
            val envelope = JSONObject()
                .put("id", eventId)
                .put("idempotencyKey", "android:wifi:${decision.normalizedSsid ?: "none"}:${System.currentTimeMillis() / 10_000L}")
                .put("type", "sensor.wifi_changed")
                .put("source", "android")
                .put("ts", System.currentTimeMillis())
                .put(
                    "payload",
                    JSONObject()
                        .put("ssid", decision.normalizedSsid ?: "")
                        .put("onWorkWifi", decision.onWorkWifi)
                        .put("stealthOffice", decision.shouldAutoStealth)
                        .put("headphonesConnected", areHeadphonesConnected())
                )
                .toString()
            EventSyncCoordinator.sendEventAsync(appContext, envelope)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSsid(capabilities: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = capabilities.transportInfo as? WifiInfo
            if (info != null) return info.ssid
        }
        val wifiManager = appContext.getSystemService(WifiManager::class.java)
        return runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
    }
}
