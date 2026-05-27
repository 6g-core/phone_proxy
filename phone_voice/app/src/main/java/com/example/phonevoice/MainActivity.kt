package com.example.phonevoice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.phonevoice.service.VoiceHttpForegroundService
import com.example.phonevoice.state.VoiceUiState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by VoiceHttpForegroundService.sharedStateStore.state.collectAsStateWithLifecycle()
            val notificationLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            MaterialTheme {
                Surface {
                    VoiceScreen(
                        state = state,
                        onStart = {
                            ContextCompat.startForegroundService(
                                this@MainActivity,
                                VoiceHttpForegroundService.startIntent(this@MainActivity),
                            )
                        },
                        onStop = {
                            startService(
                                VoiceHttpForegroundService.stopIntent(this@MainActivity),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceScreen(
    state: VoiceUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val topGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFFFF2D8),
            Color(0xFFFFD8B8),
            Color(0xFFFFF8EF),
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(topGradient)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2A44)),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Word2Voice",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Local LAN speech trigger for Android phones",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFE9EEF7),
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBF5)),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusRow(label = "Status", value = state.status.name)
                StatusRow(label = "LAN URL", value = state.lanUrl)
                StatusRow(label = "TTS Ready", value = state.ttsReady.toString())
                StatusRow(
                    label = "Last Text",
                    value = state.lastText.ifBlank { "None" },
                )
                if (state.lastError.isNotBlank()) {
                    StatusRow(label = "Last Error", value = state.lastError)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Start Service")
            }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Stop Service")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0x66FFFFFF),
                    shape = RoundedCornerShape(20.dp),
                )
                .padding(18.dp),
        ) {
            Text(
                text = "Send POST JSON to /api/v1/word2voice with a text field. The phone will speak it immediately once TTS is ready.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF2D3748),
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF6B7280),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF111827),
        )
    }
}
