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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.neko.secretary.perception.ShareIntakeHandler
import com.neko.secretary.protocol.AssistantMode
import com.neko.secretary.protocol.CharacterDirective
import com.neko.secretary.protocol.CharacterStateId
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

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

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NekoHomeConsole(
                        onStartOverlay = ::startOverlay,
                        onStopOverlay = { stopService(CompanionOverlayService.intent(this)) },
                        onToggleStealth = { StealthController.set(this, it) },
                        onImportModel = { modelPicker.launch(arrayOf("application/zip", "application/octet-stream")) }
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
            ShareIntakeHandler.extractSharedPayload(intent)
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

private fun demoDirective(state: CharacterStateId, text: String, mode: AssistantMode): CharacterDirective =
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
        ttsCacheKey = null
    )

@Composable
fun NekoHomeConsole(
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onToggleStealth: (Boolean) -> Unit,
    onImportModel: () -> Unit
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
    var ttsEnabled by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartOverlay) { Text("Start Overlay") }
            OutlinedButton(onClick = onStopOverlay) { Text("Stop") }
            OutlinedButton(onClick = onImportModel) { Text("Import .zip") }
        }

        Button(onClick = {
            val next = if (mode == AssistantMode.LIFE) AssistantMode.SECRETARY else AssistantMode.LIFE
            CharacterStateMachine.onModeChanged(next)
        }) {
            Text(if (mode == AssistantMode.LIFE) "Switch to Secretary" else "Switch to Life Maid")
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
                    demoDirective(CharacterStateId.LIFE_WATER, "主人，该喝水啦~ 抱着水杯等你哦", mode)
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
    }
}
