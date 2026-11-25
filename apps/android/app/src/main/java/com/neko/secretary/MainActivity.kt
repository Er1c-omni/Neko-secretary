package com.neko.secretary

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neko.secretary.live2d.CharacterStateMachine
import com.neko.secretary.overlay.CompanionOverlayService
import com.neko.secretary.perception.ShareIntakeHandler
import com.neko.secretary.protocol.AssistantMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NekoHomeConsole(
                        onToggleOverlay = { startService(Intent(this, CompanionOverlayService::class.java)) }
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
}

@Composable
fun NekoHomeConsole(onToggleOverlay: () -> Unit) {
    var mode by remember { mutableStateOf(AssistantMode.LIFE) }
    var stealthOffice by remember { mutableStateOf(false) }
    var ttsEnabled by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Neko-Secretary Console", style = MaterialTheme.typography.headlineSmall)
        Text("Current Mode: ${mode.wireName} | State: ${CharacterStateMachine.currentState.value}")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                mode = if (mode == AssistantMode.LIFE) AssistantMode.SECRETARY else AssistantMode.LIFE
                CharacterStateMachine.onModeChanged(mode, stealthOffice)
            }) {
                Text(if (mode == AssistantMode.LIFE) "Switch to Secretary" else "Switch to Life Maid")
            }
            Button(onClick = onToggleOverlay) {
                Text("Start Live2D Overlay")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Office Stealth Mode")
            Switch(checked = stealthOffice, onCheckedChange = {
                stealthOffice = it
                CharacterStateMachine.onStealthChanged(it, mode)
            })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Optional TTS (auto-muted in stealth)")
            Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it })
        }
    }
}
