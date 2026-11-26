package com.neko.secretary

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neko.secretary.live2d.CharacterStateMachine
import com.neko.secretary.live2d.Live2DModelRepository
import com.neko.secretary.overlay.CompanionOverlayService
import com.neko.secretary.overlay.OverlayRuntime
import com.neko.secretary.overlay.StealthController
import com.neko.secretary.perception.HubEndpointConfig
import com.neko.secretary.perception.HubEndpointStore
import com.neko.secretary.perception.ShareIntakeHandler
import com.neko.secretary.protocol.AssistantMode
import com.neko.secretary.protocol.CharacterDirective
import com.neko.secretary.protocol.CharacterStateId
import com.neko.secretary.protocol.DirectiveAction
import com.neko.secretary.sync.EventSyncCoordinator
import com.neko.secretary.sync.OfflineReminderScheduler
import com.neko.secretary.voice.AsrConfig
import com.neko.secretary.voice.AsrConfigStore
import com.neko.secretary.voice.AsrProviderKind
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(
                this,
                if (granted) "麦克风权限已开启，可长按桌宠说话" else "未授予麦克风权限，无法语音输入",
                Toast.LENGTH_SHORT
            ).show()
        }

    private val modelPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importModel(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        StealthController.restore(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val initialAsrConfig = AsrConfigStore.load(this)
        val initialHubConfig = HubEndpointStore.load(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NekoHomeConsole(
                        initialAsrConfig = initialAsrConfig,
                        initialHubConfig = initialHubConfig,
                        onStartOverlay = ::startOverlay,
                        onStopOverlay = { stopService(CompanionOverlayService.intent(this)) },
                        onToggleStealth = { StealthController.set(this, it) },
                        onImportModel = { modelPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                        onRequestAudioPermission = { audioPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        onSaveAsrConfig = { cfg ->
                            AsrConfigStore.save(this, cfg)
                            Toast.makeText(this, "已保存 ASR 配置 (${cfg.provider.wireName})", Toast.LENGTH_SHORT).show()
                        },
                        onSaveHubConfig = { cfg ->
                            HubEndpointStore.save(this, cfg)
                            if (cfg.baseUrl.isNotBlank()) {
                                val wsUrl = cfg.baseUrl
                                    .replaceFirst("https://", "wss://")
                                    .replaceFirst("http://", "ws://")
                                    .trimEnd('/') + "/ws"
                                EventSyncCoordinator.connect(this, wsUrl, cfg.bearerToken.ifBlank { null })
                            }
                            OfflineReminderScheduler.scheduleWaterFallback(this, 60)
                            Toast.makeText(this, "已保存 Core Hub 地址并开启离线兜底闹钟", Toast.LENGTH_SHORT).show()
                        },
                        onConfirmShareCard = { ShareIntakeHandler.confirmPending(this) },
                        onDismissShareCard = { ShareIntakeHandler.dismissPending(this) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            ShareIntakeHandler.ingestIntent(this, intent)
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予“显示在其他应用上层”权限后再启动桌宠", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        CompanionOverlayService.start(this)
    }

    private fun importModel(uri: Uri) {
        thread(name = "live2d-import") {
            val result = Live2DModelRepository.importZip(this, uri)
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, "已导入模型：${it.displayName}", Toast.LENGTH_SHORT).show()
                    if (OverlayRuntime.running.value) {
                        CompanionOverlayService.start(this, CompanionOverlayService.ACTION_RELOAD_MODEL)
                    }
                }.onFailure {
                    Toast.makeText(this, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

private fun demoDirective(
    state: CharacterStateId,
    text: String,
    mode: AssistantMode,
    actions: List<DirectiveAction> = emptyList()
): CharacterDirective =
    CharacterDirective(
        directiveId = UUID.randomUUID().toString(),
        mode = mode,
        state = state,
        stealthOverride = false,
        text = text,
        expression = "",
        motion = "",
        costume = if (mode == AssistantMode.SECRETARY) "secretary" else "default",
        ttsAllowed = false,
        ttsCacheKey = null,
        actions = actions
    )

@Composable
fun NekoHomeConsole(
    initialAsrConfig: AsrConfig,
    initialHubConfig: HubEndpointConfig,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onToggleStealth: (Boolean) -> Unit,
    onImportModel: () -> Unit,
    onRequestAudioPermission: () -> Unit,
    onSaveAsrConfig: (AsrConfig) -> Unit,
    onSaveHubConfig: (HubEndpointConfig) -> Unit,
    onConfirmShareCard: () -> Unit,
    onDismissShareCard: () -> Unit
) {
    val state by CharacterStateMachine.currentState.collectAsState()
    val mode by CharacterStateMachine.mode.collectAsState()
    val stealthOffice by CharacterStateMachine.stealthOffice.collectAsState()
    val running by OverlayRuntime.running.collectAsState()
    val targetFps by OverlayRuntime.targetFps.collectAsState()
    val measuredFps by OverlayRuntime.measuredFps.collectAsState()
    val backend by OverlayRuntime.backend.collectAsState()
    val docked by OverlayRuntime.docked.collectAsState()
    val passThrough by OverlayRuntime.passThrough.collectAsState()
    val voiceActive by OverlayRuntime.voiceActive.collectAsState()
    val voiceProvider by OverlayRuntime.voiceProvider.collectAsState()
    val lastTranscript by OverlayRuntime.lastTranscript.collectAsState()
    val pendingShareCard by ShareIntakeHandler.pendingCard.collectAsState()
    val shareUploading by ShareIntakeHandler.uploading.collectAsState()
    val shareStatus by ShareIntakeHandler.statusMessage.collectAsState()
    var ttsEnabled by remember { mutableStateOf(false) }

    var hubUrl by remember { mutableStateOf(initialHubConfig.baseUrl) }
    var hubToken by remember { mutableStateOf(initialHubConfig.bearerToken) }
    var asrProvider by remember { mutableStateOf(initialAsrConfig.provider) }
    var asrWsUrl by remember { mutableStateOf(initialAsrConfig.wsUrl) }
    var asrAppId by remember { mutableStateOf(initialAsrConfig.appId) }
    var asrToken by remember { mutableStateOf(initialAsrConfig.accessToken) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Neko-Secretary Console", style = MaterialTheme.typography.headlineSmall)
        Text("Mode: ${mode.wireName} | State: $state")
        Text(
            "Overlay: ${if (running) "running" else "stopped"} | backend=$backend | " +
                "target=${targetFps}fps measured=${measuredFps}fps | " +
                "${if (docked) "peek" else "expanded"}${if (passThrough) " | passthrough" else ""}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Voice ASR: ${if (voiceActive) "recording" else "idle"} ($voiceProvider)" +
                if (lastTranscript.isNotBlank()) " | last: $lastTranscript" else "",
            style = MaterialTheme.typography.bodySmall
        )

        if (shareUploading || pendingShareCard != null || shareStatus != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (shareUploading) "截图分享解析中…" else "分享解析卡片 (${pendingShareCard?.kind ?: "status"})",
                        style = MaterialTheme.typography.titleSmall
                    )
                    pendingShareCard?.let { card ->
                        Text(card.title, style = MaterialTheme.typography.titleMedium)
                        Text(card.summary, style = MaterialTheme.typography.bodyMedium)
                        if (card.amount != null) {
                            Text("金额：${card.currency ?: "CNY"} ${card.amount}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onConfirmShareCard) { Text("一键入库") }
                            OutlinedButton(onClick = onDismissShareCard) { Text("忽略") }
                        }
                    }
                    shareStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartOverlay) { Text("Start Overlay") }
            OutlinedButton(onClick = onStopOverlay) { Text("Stop") }
            OutlinedButton(onClick = onImportModel) { Text("Import .zip") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val next = if (mode == AssistantMode.LIFE) AssistantMode.SECRETARY else AssistantMode.LIFE
                CharacterStateMachine.onModeChanged(next)
            }) {
                Text(if (mode == AssistantMode.LIFE) "Switch to Secretary" else "Switch to Life Maid")
            }
            OutlinedButton(onClick = onRequestAudioPermission) { Text("Mic Permission") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("职场防社死一键隐身 (0 FPS)")
            Switch(checked = stealthOffice, onCheckedChange = onToggleStealth)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Optional TTS (auto-muted in stealth)")
            Switch(checked = ttsEnabled && !stealthOffice, enabled = !stealthOffice, onCheckedChange = { ttsEnabled = it })
        }

        Text("Directive preview (local, 0 token)", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                CharacterStateMachine.applyDirective(
                    demoDirective(
                        CharacterStateId.LIFE_WATER,
                        "主人，该喝水啦~ 抱着水杯等你哦",
                        mode,
                        actions = listOf(
                            DirectiveAction("water_done:job_water_hourly", "喝完啦", "happy_ear_wiggle"),
                            DirectiveAction("water_snooze_5:job_water_hourly", "5m后", "puff_cheeks")
                        )
                    )
                )
            }) { Text("Water") }
            OutlinedButton(onClick = {
                CharacterStateMachine.applyDirective(
                    demoDirective(CharacterStateId.SECRETARY_ALERT, "指挥官，实验机训练任务报错，请查看。", AssistantMode.SECRETARY)
                )
            }) { Text("Alert") }
            OutlinedButton(onClick = {
                CharacterStateMachine.applyDirective(
                    demoDirective(CharacterStateId.SLEEPY_NIGHT, "已经很晚了，早点休息吧~", mode)
                )
            }) { Text("Sleepy") }
        }

        HorizontalDivider()
        Text("Core Hub Endpoint (截图吞噬 & 同步)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = hubUrl,
            onValueChange = { hubUrl = it },
            label = { Text("Hub HTTP URL (e.g. http://10.0.2.2:8787)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = hubToken,
            onValueChange = { hubToken = it },
            label = { Text("Hub Bearer Token (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { onSaveHubConfig(HubEndpointConfig(hubUrl, hubToken)) }) {
            Text("Save Hub Config")
        }

        HorizontalDivider()
        Text("Streaming ASR Config (长按桌宠说话)", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AsrProviderKind.entries.forEach { kind ->
                if (asrProvider == kind) {
                    Button(onClick = { asrProvider = kind }) { Text(kind.wireName) }
                } else {
                    OutlinedButton(onClick = { asrProvider = kind }) { Text(kind.wireName) }
                }
            }
        }
        OutlinedTextField(
            value = asrWsUrl,
            onValueChange = { asrWsUrl = it },
            label = { Text("WebSocket URL (wss://...)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = asrAppId,
            onValueChange = { asrAppId = it },
            label = { Text("App ID / Key (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = asrToken,
            onValueChange = { asrToken = it },
            label = { Text("Access Token (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            onSaveAsrConfig(
                AsrConfig(
                    provider = asrProvider,
                    wsUrl = asrWsUrl,
                    appId = asrAppId,
                    accessToken = asrToken,
                    offlineFallbackEnabled = true
                )
            )
        }) {
            Text("Save ASR Config")
        }
    }
}
